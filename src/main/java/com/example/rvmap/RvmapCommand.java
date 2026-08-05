package com.example.rvmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /rvmap copy}、{@code /rvmap build}、{@code /rvmap palette [regen]}。
 * ClientCommandManager で登録するのでサーバーには送られない。
 */
public final class RvmapCommand {
	private static final String PNG = ".png";
	private static final String NBT = ".nbt";

	private RvmapCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal(Rvmap.MOD_ID)
				.then(ClientCommandManager.literal("copy")
						.executes(context -> copy(context, null))
						.then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
								.executes(context -> copy(context, StringArgumentType.getString(context, "filename")))))
				.then(ClientCommandManager.literal("build")
						.executes(context -> build(context, null))
						.then(ClientCommandManager.argument("filename", StringArgumentType.greedyString())
								.executes(context -> build(context, StringArgumentType.getString(context, "filename")))))
				.then(ClientCommandManager.literal("palette")
						.executes(RvmapCommand::showPalette)
						.then(ClientCommandManager.literal("regen")
								.executes(RvmapCommand::regeneratePalette))));
	}

	// ------------------------------------------------------------------ copy

	private static int copy(CommandContext<FabricClientCommandSource> context, String rawName) {
		FabricClientCommandSource source = context.getSource();
		MinecraftClient client = source.getClient();

		MapTargeting.Result result = MapTargeting.resolve(client);

		if (result instanceof MapTargeting.Error error) {
			Chat.error(source, MapTargeting.failureMessage(error.failure()));
			return 0;
		}

		MapTargeting.Target target = ((MapTargeting.Success) result).target();
		MapState state = target.state();

		int mapId = target.mapId().id();
		String fallback = "map_" + mapId + "_" + RvmapPaths.timestamp() + PNG;
		String fileName = rawName == null ? fallback : RvmapPaths.sanitizeFileName(rawName, PNG, fallback);

		// colors はサーバーからのパケットで書き換わるので、ゲームスレッドのうちに複製しておく。
		byte[] colors = state.colors.clone();

		if (colors.length != Rvmap.MAP_SIZE * Rvmap.MAP_SIZE) {
			Chat.error(source, "地図データの大きさが想定外です (" + colors.length + " バイト)。");
			return 0;
		}

		Chat.info(source, "地図 #" + mapId + " を書き出しています…");

		IoWorker.submit(() -> {
			try {
				Path destination = RvmapPaths.outputDirectory().resolve(fileName);
				MapImageWriter.writePng(colors, destination);
				client.execute(() -> Chat.savedTo(source, destination));
			} catch (IOException | RuntimeException e) {
				reportWriteFailure(client, source, "map PNG", e);
			}
		});

		return 1;
	}

	// ----------------------------------------------------------------- build

	private static int build(CommandContext<FabricClientCommandSource> context, String rawName) {
		FabricClientCommandSource source = context.getSource();
		MinecraftClient client = source.getClient();

		MapTargeting.Result result = MapTargeting.resolve(client);

		if (result instanceof MapTargeting.Error error) {
			Chat.error(source, MapTargeting.failureMessage(error.failure()));
			return 0;
		}

		// 実行のたびに palette.json を読み直し、レジストリと照合する。
		// 手で編集されていてもここで必ず引っかかる。
		PaletteConfig.LoadResult loaded = PaletteConfig.load();

		if (!loaded.ok()) {
			Chat.error(source, PaletteConfig.FILE_NAME + " に問題があるため生成を中止しました:");
			loaded.errors().forEach(message -> Chat.error(source, "  - " + message));
			return 0;
		}

		if (loaded.created()) {
			Chat.warn(source, PaletteConfig.FILE_NAME + " が無かったので生成しました。");
			reportGeneration(source, loaded.generated(), false);
		}

		MapTargeting.Target target = ((MapTargeting.Success) result).target();
		MapState state = target.state();

		int mapId = target.mapId().id();
		String fallback = "map_" + mapId + "_" + RvmapPaths.timestamp() + NBT;
		String fileName = rawName == null ? fallback : RvmapPaths.sanitizeFileName(rawName, NBT, fallback);

		byte[] colors = state.colors.clone();

		if (colors.length != Rvmap.MAP_SIZE * Rvmap.MAP_SIZE) {
			Chat.error(source, "地図データの大きさが想定外です (" + colors.length + " バイト)。");
			return 0;
		}

		// 高さの計算は縮尺 1:1(scale 0)を前提にしている。
		if (state.scale != 0) {
			Chat.warn(source, "この地図の縮尺は 1:" + (1 << state.scale)
					+ " です。高さの計算は縮尺 1:1 前提なので、そのままでは正しい像になりません。");
		}

		SchematicBuilder.Result schematic;

		try {
			schematic = SchematicBuilder.build(colors, loaded.config());
		} catch (SchematicBuilder.UnsupportedShadeException e) {
			Chat.error(source, "明度3 のピクセルが (x=" + e.pixelX() + ", z=" + e.pixelZ() + ") にあります。");
			Chat.error(source, "これは水深の表現で、縮尺1:1 の階段状マップアートには変換できません。この地図は非対応です。");
			return 0;
		} catch (SchematicBuilder.UndefinedColorException e) {
			Chat.error(source, "この地図には、対応表に無い基本色IDが使われているため生成を中止しました:");

			for (int colorId : e.colorIds()) {
				Chat.error(source, "  - 色ID " + colorId + " (" + PaletteConfig.describeColor(colorId) + ")");
			}

			Chat.error(source, PaletteConfig.FILE_NAME + " に該当の色IDとブロックを追記してから再実行してください。");
			return 0;
		}

		Chat.info(source, "地図 #" + mapId + " を .nbt に変換しています…");

		IoWorker.submit(() -> {
			try {
				Path destination = RvmapPaths.outputDirectory().resolve(fileName);
				StructureNbtWriter.write(schematic, destination);
				client.execute(() -> {
					Chat.savedTo(source, destination);
					report(source, schematic);
				});
			} catch (IOException | RuntimeException e) {
				reportWriteFailure(client, source, "structure NBT", e);
			}
		});

		return 1;
	}

	private static void report(FabricClientCommandSource source, SchematicBuilder.Result schematic) {
		Chat.info(source, "領域サイズ: X=" + schematic.sizeX()
				+ " Y=" + schematic.sizeY()
				+ " Z=" + schematic.sizeZ() + " (z=0 がヌーブライン)");
		Chat.info(source, "最大高さ: y=" + schematic.maxY()
				+ " / 最も高い列: x=" + schematic.tallestColumnX());

		Chat.info(source, "必要な建材 (" + schematic.blockCounts().size() + " 種類, 合計 "
				+ schematic.placements().size() + " 個):");

		List<Map.Entry<String, Integer>> sorted = schematic.blockCounts().entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.toList();

		for (Map.Entry<String, Integer> entry : sorted) {
			source.sendFeedback(Text.literal("  " + entry.getKey() + " x " + entry.getValue())
					.formatted(Formatting.AQUA));
		}

		if (schematic.transparentPixels() > 0) {
			Chat.warn(source, "未探索(基本色ID 0)のピクセルが " + schematic.transparentPixels()
					+ " 個ありました。そこは空気になるため、列の連続性が途切れます。");
		}
	}

	// --------------------------------------------------------------- palette

	private static int showPalette(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		PaletteConfig.LoadResult loaded = PaletteConfig.load();

		if (!loaded.ok()) {
			Chat.error(source, PaletteConfig.FILE_NAME + " を読み込めませんでした:");
			loaded.errors().forEach(message -> Chat.error(source, "  - " + message));
			return 0;
		}

		if (loaded.created()) {
			Chat.warn(source, PaletteConfig.FILE_NAME + " が無かったので生成しました。");
			reportGeneration(source, loaded.generated(), true);
			return 1;
		}

		PaletteConfig config = loaded.config();

		Chat.info(source, "現在の対応表 (" + config.blocks().size() + " 色):");

		for (String line : config.describeEntries()) {
			source.sendFeedback(Text.literal("  " + line).formatted(Formatting.AQUA));
		}

		Chat.info(source, "ヌーブライン: " + StructureNbtWriter.describe(config.noobLine()));

		if (!config.unused().isEmpty()) {
			Chat.info(source, "衝突で不採用 (" + PaletteConfig.UNUSED_KEY + "):");

			for (Map.Entry<String, Integer> entry : config.unused().entrySet()) {
				source.sendFeedback(Text.literal("  " + entry.getKey() + " -> 色ID " + entry.getValue())
						.formatted(Formatting.DARK_GRAY));
			}
		}

		reportMissing(source, config.missingColorIds());
		return 1;
	}

	private static int regeneratePalette(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();

		PaletteConfig.GenerationResult generated;

		try {
			generated = PaletteConfig.regenerate();
		} catch (IOException | RuntimeException e) {
			Rvmap.LOGGER.error("Failed to regenerate {}", PaletteConfig.FILE_NAME, e);
			Chat.error(source, PaletteConfig.FILE_NAME + " を再生成できませんでした: " + e);
			return 0;
		}

		Chat.warn(source, PaletteConfig.FILE_NAME + " を既定のブロックリストから再生成しました(手動編集は破棄されました)。");
		reportGeneration(source, generated, true);
		return 1;
	}

	/**
	 * 生成結果をチャットに出す。{@code verbose} なら全ブロックの割り当てを 1 行ずつ出す。
	 * 同じ内容は {@link PaletteReport} からログにも出している。
	 */
	private static void reportGeneration(FabricClientCommandSource source, PaletteConfig.GenerationResult generated,
			boolean verbose) {
		if (generated == null) {
			return;
		}

		Chat.info(source, "ブロックリスト " + PaletteConfig.DEFAULT_BLOCKS.size() + " 個の割り当て結果 ("
				+ generated.palette().size() + " 色が確定):");

		if (verbose) {
			for (PaletteConfig.Assignment assignment : generated.assignments()) {
				source.sendFeedback(Text.literal("  " + PaletteReport.line(assignment))
						.formatted(PaletteReport.colorOf(assignment)));
			}
		}

		for (PaletteConfig.Assignment assignment : generated.assignments()) {
			if (assignment.failed()) {
				Chat.error(source, "  " + assignment.blockId() + " を解決できませんでした: " + assignment.error());
			} else if (!assignment.accepted()) {
				Chat.warn(source, "  色ID " + assignment.colorId() + " が衝突: "
						+ assignment.collidesWith() + " を採用し、" + assignment.blockId()
						+ " は " + PaletteConfig.UNUSED_KEY + " に退避しました。");
			}
		}

		reportMissing(source, generated.missingColorIds());
	}

	private static void reportMissing(FabricClientCommandSource source, List<Integer> missing) {
		if (missing.isEmpty()) {
			return;
		}

		String ids = missing.stream().map(String::valueOf).collect(Collectors.joining(", "));
		Chat.warn(source, "色ID " + PaletteConfig.EXPECTED_MIN_COLOR_ID + "〜"
				+ PaletteConfig.EXPECTED_MAX_COLOR_ID + " のうち、対応ブロックが無い色ID: " + ids);
		Chat.warn(source, "これらの色が地図に出現した場合、/rvmap build は中止されます。"
				+ PaletteConfig.FILE_NAME + " に追記してください。");
	}

	/** IO ワーカーから呼ばれる。チャットへの通知はゲームスレッドに戻してから行う。 */
	private static void reportWriteFailure(MinecraftClient client, FabricClientCommandSource source,
			String what, Exception cause) {
		Rvmap.LOGGER.error("Failed to write {}", what, cause);
		client.execute(() -> Chat.error(source, "保存に失敗しました: " + cause));
	}
}
