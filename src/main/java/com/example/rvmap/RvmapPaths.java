package com.example.rvmap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.MinecraftClient;

/**
 * 出力先ディレクトリの解決と、ユーザー入力ファイル名のサニタイズ。
 *
 * <p>基点は {@link MinecraftClient#runDirectory}(= 起動構成のディレクトリ)なので、
 * MultiMC / Prism などでインスタンスごとにディレクトリが分かれていても正しく動く。
 */
public final class RvmapPaths {
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

	/** Windows の予約デバイス名。拡張子を付けても使えないため退避させる。 */
	private static final Set<String> RESERVED = Set.of(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

	private RvmapPaths() {
	}

	/** {@code <実行ディレクトリ>/rvmap}。存在しなければ作成する。 */
	public static Path outputDirectory() throws IOException {
		Path dir = runDirectory().resolve(Rvmap.MOD_ID);
		Files.createDirectories(dir);
		return dir;
	}

	public static Path runDirectory() {
		File dir = MinecraftClient.getInstance().runDirectory;
		return dir.toPath().toAbsolutePath().normalize();
	}

	/** チャットに出す用の、実行ディレクトリからの相対パス。 */
	public static String relativize(Path file) {
		Path base = runDirectory();
		Path abs = file.toAbsolutePath().normalize();
		if (abs.startsWith(base)) {
			return base.relativize(abs).toString().replace(File.separatorChar, '/');
		}
		return abs.toString();
	}

	public static String timestamp() {
		return LocalDateTime.now().format(TIMESTAMP);
	}

	/**
	 * ユーザーが打ち込んだファイル名を、rvmap ディレクトリの直下に落ちる 1 個のファイル名に潰す。
	 *
	 * <p>ディレクトリ区切り(/ と \)を含む場合は最後の要素だけを採用し、{@code ..} や
	 * ドライブレターの類は許可文字の絞り込みで落とす。結果が空になった場合は
	 * {@code fallback} を返す。
	 */
	public static String sanitizeFileName(String raw, String extension, String fallback) {
		String s = raw.trim().replace('\\', '/');

		int slash = s.lastIndexOf('/');
		if (slash >= 0) {
			s = s.substring(slash + 1);
		}

		// 制御文字・Windows で使えない記号をまとめて弾くため、許可リスト方式にする。
		// 日本語のファイル名は使いたいので Unicode の文字・数字は通す。
		s = s.replaceAll("[^\\p{L}\\p{N}._\\-]", "_");

		// 先頭のドットを落とすことで "." ".." および隠しファイル化を防ぐ。
		while (s.startsWith(".")) {
			s = s.substring(1);
		}

		while (s.endsWith(".") || s.endsWith(" ")) {
			s = s.substring(0, s.length() - 1);
		}

		if (s.length() > 100) {
			s = s.substring(0, 100);
		}

		if (s.isEmpty()) {
			return fallback;
		}

		String withExtension = s.toLowerCase(Locale.ROOT).endsWith(extension) ? s : s + extension;
		String stem = withExtension.substring(0, withExtension.length() - extension.length());

		if (RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
			return "_" + withExtension;
		}

		return withExtension;
	}
}
