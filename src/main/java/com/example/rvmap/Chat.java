package com.example.rvmap;

import java.nio.file.Path;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class Chat {
	private static final String PREFIX = "[rvmap] ";

	private Chat() {
	}

	public static void info(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(PREFIX + message).formatted(Formatting.GRAY));
	}

	public static void success(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(PREFIX + message).formatted(Formatting.GREEN));
	}

	public static void warn(FabricClientCommandSource source, String message) {
		source.sendFeedback(Text.literal(PREFIX + message).formatted(Formatting.YELLOW));
	}

	/** sendError の見た目に依存しないよう、こちら側でも赤を付けておく。 */
	public static void error(FabricClientCommandSource source, String message) {
		source.sendError(Text.literal(PREFIX + message).formatted(Formatting.RED));
	}

	/** コマンド以外(ワールド参加時の設定エラー通知など)からチャットに出す。 */
	public static void toHud(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud != null) {
			client.inGameHud.getChatHud().addMessage(text);
		}
	}

	public static void errorToHud(String message) {
		toHud(Text.literal(PREFIX + message).formatted(Formatting.RED));
	}

	/**
	 * 保存先の相対パスを緑字で出す。クリックするとファイルマネージャで
	 * rvmap フォルダが開く({@link ClickEvent.Action#OPEN_FILE} はクライアント側で
	 * 実行されるため、クライアント専用MODでも動作する)。
	 */
	public static void savedTo(FabricClientCommandSource source, Path file) {
		String relative = RvmapPaths.relativize(file);
		Path folder = file.toAbsolutePath().getParent();

		MutableText link = Text.literal(relative)
				.formatted(Formatting.GREEN, Formatting.UNDERLINE)
				.styled(style -> style
						.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, folder.toString()))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
								Text.literal("クリックでフォルダを開く: " + folder))));

		source.sendFeedback(Text.literal(PREFIX + "保存しました: ").formatted(Formatting.GREEN).append(link));
	}
}
