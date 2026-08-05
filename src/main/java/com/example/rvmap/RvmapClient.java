package com.example.rvmap;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class RvmapClient implements ClientModInitializer {
	/** 起動時の検証で出たエラー。チャットが使えるようになったタイミングで流す。 */
	private static final List<String> STARTUP_ERRORS = new ArrayList<>();

	/** 起動時に palette.json を生成した場合の結果。1 回だけチャットに流す。 */
	private static PaletteConfig.GenerationResult startupGeneration;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess) -> RvmapCommand.register(dispatcher));

		// MinecraftClient.runDirectory を基点にしたいので、インスタンスが確実に出来上がってから検証する。
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> validatePaletteAtStartup());

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> flushStartupReport());
	}

	/**
	 * 起動時に palette.json を(無ければレジストリから生成してから)検証する。
	 * この時点ではチャットが無いのでログに出し、ワールド参加時に改めてチャットへ流す。
	 */
	private void validatePaletteAtStartup() {
		PaletteConfig.LoadResult result;

		try {
			result = PaletteConfig.load();
		} catch (RuntimeException e) {
			Rvmap.LOGGER.error("Failed to validate {}", PaletteConfig.FILE_NAME, e);
			STARTUP_ERRORS.add("検証中に例外が発生しました: " + e);
			return;
		}

		if (result.created()) {
			startupGeneration = result.generated();
			Rvmap.LOGGER.info("Generated {} from the registry ({} colours from {} blocks)",
					PaletteConfig.FILE_NAME,
					result.generated().palette().size(),
					PaletteConfig.DEFAULT_BLOCKS.size());
			PaletteReport.logLines(result.generated()).forEach(line -> Rvmap.LOGGER.info("  {}", line));
		}

		if (result.ok()) {
			Rvmap.LOGGER.info("{} validated: {} colour(s) mapped",
					PaletteConfig.FILE_NAME, result.config().blocks().size());

			List<Integer> missing = result.config().missingColorIds();

			if (!missing.isEmpty()) {
				Rvmap.LOGGER.warn("{}: no block for colour id(s) {}", PaletteConfig.FILE_NAME, missing);
			}

			return;
		}

		STARTUP_ERRORS.addAll(result.errors());
		result.errors().forEach(message -> Rvmap.LOGGER.error("{}: {}", PaletteConfig.FILE_NAME, message));
	}

	/** ワールド参加時に、起動時の生成結果とエラーをチャットへ流す(それぞれ 1 回だけ)。 */
	private static void flushStartupReport() {
		if (startupGeneration != null) {
			PaletteConfig.GenerationResult generated = startupGeneration;
			startupGeneration = null;

			Chat.toHud(Text.literal("[rvmap] " + PaletteConfig.FILE_NAME
					+ " をゲームのレジストリから自動生成しました。").formatted(Formatting.GREEN));

			for (PaletteConfig.Assignment assignment : generated.assignments()) {
				Chat.toHud(Text.literal("  " + PaletteReport.line(assignment))
						.formatted(PaletteReport.colorOf(assignment)));
			}

			if (!generated.missingColorIds().isEmpty()) {
				Chat.toHud(Text.literal("[rvmap] 対応ブロックが無い色ID: " + generated.missingColorIds()
						+ " (この色が地図に出ると /rvmap build は中止されます)").formatted(Formatting.YELLOW));
			}
		}

		if (STARTUP_ERRORS.isEmpty()) {
			return;
		}

		Chat.errorToHud(PaletteConfig.FILE_NAME + " に問題があります。/rvmap build は実行できません:");
		STARTUP_ERRORS.forEach(message -> Chat.errorToHud("  - " + message));
	}
}
