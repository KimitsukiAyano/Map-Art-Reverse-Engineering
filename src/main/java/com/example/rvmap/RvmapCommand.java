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
import net.minecraft.util.Formatting;
import net.minecraft.text.Text;

/**
 * {@code /rvmap copy [ファイル名]} と {@code /rvmap build [ファイル名]}。
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
								.executes(context -> build(context, StringArgumentType.getString(context, "filename"))))));
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
		PaletteConfig.LoadResult loaded = PaletteConfig.load();

		if (!loaded.ok()) {
			Chat.error(source, PaletteConfig.FILE_NAME + " に問題があるため生成を中止しました:");
			loaded.errors().forEach(message -> Chat.error(source, "  - " + message));
			return 0;
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

	/** IO ワーカーから呼ばれる。チャットへの通知はゲームスレッドに戻してから行う。 */
	private static void reportWriteFailure(MinecraftClient client, FabricClientCommandSource source,
			String what, Exception cause) {
		Rvmap.LOGGER.error("Failed to write {}", what, cause);
		client.execute(() -> Chat.error(source, "保存に失敗しました: " + cause));
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
				.collect(Collectors.toList());

		for (Map.Entry<String, Integer> entry : sorted) {
			source.sendFeedback(Text.literal("  " + entry.getKey() + " x " + entry.getValue())
					.formatted(Formatting.AQUA));
		}

		if (schematic.transparentPixels() > 0) {
			Chat.warn(source, "未探索(基本色ID 0)のピクセルが " + schematic.transparentPixels()
					+ " 個ありました。そこは空気になるため、列の連続性が途切れます。");
		}

		if (!schematic.undefinedColorIds().isEmpty()) {
			String ids = schematic.undefinedColorIds().stream()
					.map(String::valueOf)
					.collect(Collectors.joining(", "));
			Chat.warn(source, "パレット未定義だった基本色ID: " + ids
					+ " (該当ピクセルは空気になっています。" + PaletteConfig.FILE_NAME + " に追記してください)");
		}
	}
}
