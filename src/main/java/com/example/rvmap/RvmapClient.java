package com.example.rvmap;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class RvmapClient implements ClientModInitializer {
	/** 起動時の検証で出たエラー。チャットが使えるようになったタイミングで流す。 */
	private static final List<String> STARTUP_ERRORS = new ArrayList<>();

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess) -> RvmapCommand.register(dispatcher));

		// MinecraftClient.runDirectory を基点にしたいので、インスタンスが確実に出来上がってから検証する。
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> validatePaletteAtStartup());

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (STARTUP_ERRORS.isEmpty()) {
				return;
			}

			Chat.errorToHud(PaletteConfig.FILE_NAME + " に問題があります。/rvmap build は実行できません:");
			STARTUP_ERRORS.forEach(message -> Chat.errorToHud("  - " + message));
		});
	}

	/**
	 * 起動時に palette.json を(無ければ作ってから)検証する。
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
			Rvmap.LOGGER.info("Created default {}", PaletteConfig.FILE_NAME);
		}

		if (result.ok()) {
			Rvmap.LOGGER.info("{} validated: {} colour(s) mapped",
					PaletteConfig.FILE_NAME, result.config().blocks().size());
			return;
		}

		STARTUP_ERRORS.addAll(result.errors());
		result.errors().forEach(message -> Rvmap.LOGGER.error("{}: {}", PaletteConfig.FILE_NAME, message));
	}
}
