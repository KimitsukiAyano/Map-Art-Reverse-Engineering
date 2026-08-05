package com.example.rvmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

/**
 * {@code rvmap/palette.json} の読み書きと検証。
 *
 * <p>形式は「基本色ID(文字列の数字) → ブロックステート文字列」。
 * それに加えて特別なキーを 2 つ受け付ける:
 * <ul>
 *   <li>{@code noob_line} … ヌーブラインに使うブロック(既定 {@code minecraft:stone})</li>
 *   <li>{@code _} で始まるキー … コメント扱いで無視する</li>
 * </ul>
 *
 * <p>検証は「指定されたブロックステートを実際にパースし、その MapColor の ID が
 * キーの基本色IDと一致するか」をゲーム内レジストリで照合する。ここでハードコードした
 * 色テーブルは使わない。
 */
public final class PaletteConfig {
	public static final String FILE_NAME = "palette.json";
	public static final String NOOB_LINE_KEY = "noob_line";
	public static final String DEFAULT_NOOB_LINE = "minecraft:stone";

	/** MapColor の ID は 6 bit(パック値の上位 6 bit)なので 0〜63。 */
	public static final int MAX_COLOR_ID = 63;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 既定のパレット。1.21.1 のブロックレジストリを実際にダンプして、
	 * 各エントリの MapColor ID が一致することを確認済み。
	 */
	private static final Map<Integer, String> DEFAULT_PALETTE = createDefaultPalette();

	private final Map<Integer, BlockState> blocks;
	private final BlockState noobLine;

	private PaletteConfig(Map<Integer, BlockState> blocks, BlockState noobLine) {
		this.blocks = blocks;
		this.noobLine = noobLine;
	}

	/** 検証済みのマップから直接組み立てる(開発用のオフラインテストからも使う)。 */
	public static PaletteConfig of(Map<Integer, BlockState> blocks, BlockState noobLine) {
		return new PaletteConfig(new TreeMap<>(blocks), noobLine);
	}

	/** 既定パレットの「基本色ID → ブロックステート文字列」。 */
	public static Map<Integer, String> defaultPalette() {
		return Collections.unmodifiableMap(DEFAULT_PALETTE);
	}

	public Map<Integer, BlockState> blocks() {
		return Collections.unmodifiableMap(blocks);
	}

	public BlockState noobLine() {
		return noobLine;
	}

	/** 読み込み結果。エラーが 1 つでもあれば {@code config} は null。 */
	public record LoadResult(PaletteConfig config, List<String> errors, boolean created) {
		public boolean ok() {
			return config != null;
		}
	}

	/**
	 * palette.json を読み込む。存在しなければ既定値で作成する。
	 * ブロックレジストリを参照するので、呼び出しはゲームスレッドから行うこと。
	 */
	public static LoadResult load() {
		Path file;

		try {
			file = RvmapPaths.outputDirectory().resolve(FILE_NAME);
		} catch (IOException e) {
			return new LoadResult(null, List.of("rvmap フォルダを作成できませんでした: " + e.getMessage()), false);
		}

		boolean created = false;

		if (!Files.exists(file)) {
			try {
				Files.writeString(file, defaultJson(), StandardCharsets.UTF_8);
				created = true;
			} catch (IOException e) {
				return new LoadResult(null, List.of(FILE_NAME + " を作成できませんでした: " + e.getMessage()), false);
			}
		}

		String json;

		try {
			json = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return new LoadResult(null, List.of(FILE_NAME + " を読み込めませんでした: " + e.getMessage()), created);
		}

		return validate(json, created);
	}

	/**
	 * palette.json の中身を検証する。ブロックレジストリを参照するので
	 * 呼び出しはゲームスレッドから行うこと。
	 */
	public static LoadResult validate(String json, boolean created) {
		JsonObject root;

		try {
			JsonElement parsed = JsonParser.parseString(json);

			if (!parsed.isJsonObject()) {
				return new LoadResult(null, List.of(FILE_NAME + " のトップレベルが JSON オブジェクトではありません。"), created);
			}

			root = parsed.getAsJsonObject();
		} catch (JsonSyntaxException e) {
			return new LoadResult(null, List.of(FILE_NAME + " の JSON を解釈できませんでした: " + e.getMessage()), created);
		}

		return parse(root, created);
	}

	private static LoadResult parse(JsonObject root, boolean created) {
		List<String> errors = new ArrayList<>();
		Map<Integer, BlockState> blocks = new TreeMap<>();
		BlockState noobLine = null;

		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			String key = entry.getKey();

			if (key.startsWith("_")) {
				continue;
			}

			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
				errors.add("キー \"" + key + "\" の値が文字列ではありません。");
				continue;
			}

			String blockString = entry.getValue().getAsString();

			if (NOOB_LINE_KEY.equals(key)) {
				BlockState state = parseBlockState(blockString, errors, "ヌーブライン");

				if (state != null) {
					noobLine = state;
				}

				continue;
			}

			int colorId;

			try {
				colorId = Integer.parseInt(key);
			} catch (NumberFormatException e) {
				errors.add("キー \"" + key + "\" は基本色ID(数字)でも \"" + NOOB_LINE_KEY + "\" でもありません。");
				continue;
			}

			if (colorId <= 0) {
				errors.add("色ID " + colorId + " は指定できません(0 は未探索/透明のため常に空気になります)。");
				continue;
			}

			if (colorId > MAX_COLOR_ID) {
				errors.add("色ID " + colorId + " は範囲外です(1〜" + MAX_COLOR_ID + " を指定してください)。");
				continue;
			}

			BlockState state = parseBlockState(blockString, errors, "色ID " + colorId);

			if (state == null) {
				continue;
			}

			int actualId = mapColorId(state);

			if (actualId != colorId) {
				errors.add("色ID " + colorId + " に指定された " + blockString
						+ " は実際には色ID " + actualId + " です"
						+ " (" + describeColor(colorId) + " ではなく " + describeColor(actualId) + ")。");
				continue;
			}

			blocks.put(colorId, state);
		}

		if (noobLine == null && errors.isEmpty()) {
			noobLine = parseBlockState(DEFAULT_NOOB_LINE, errors, "ヌーブライン");
		}

		if (!errors.isEmpty()) {
			return new LoadResult(null, errors, created);
		}

		return new LoadResult(new PaletteConfig(blocks, noobLine), List.of(), created);
	}

	private static BlockState parseBlockState(String blockString, List<String> errors, String label) {
		try {
			BlockArgumentParser.BlockResult result =
					BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), blockString, false);
			return result.blockState();
		} catch (CommandSyntaxException e) {
			errors.add(label + " に指定された \"" + blockString + "\" を解釈できませんでした: " + e.getMessage());
			return null;
		}
	}

	/**
	 * BlockState の MapColor ID を引く。
	 * {@code getMapColor} は BlockView/BlockPos を取るが、バニラのブロックはほぼ位置を見ないので
	 * 空のビューを渡す。
	 */
	public static int mapColorId(BlockState state) {
		MapColor color = state.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
		return color == null ? 0 : color.id;
	}

	private static String describeColor(int colorId) {
		if (colorId == 0) {
			return "透明/未探索";
		}

		try {
			MapColor color = MapColor.get(colorId);
			return String.format("#%06X", color.color);
		} catch (RuntimeException e) {
			// MapColor.get は範囲外の ID で例外を投げる。エラーメッセージの組み立てで
			// 落ちないよう握り潰しておく。
			return "未使用の色ID";
		}
	}

	public static String defaultJson() {
		Map<String, String> out = new LinkedHashMap<>();
		out.put("_comment", "基本色ID -> ブロックステート文字列。/rvmap build のときに実際のレジストリと照合されます。");
		out.put("_comment2", "\"" + NOOB_LINE_KEY + "\" は最北端に足す 1 行(ヌーブライン)のブロックです。");
		out.put(NOOB_LINE_KEY, DEFAULT_NOOB_LINE);

		for (Map.Entry<Integer, String> entry : DEFAULT_PALETTE.entrySet()) {
			out.put(String.valueOf(entry.getKey()), entry.getValue());
		}

		return GSON.toJson(out) + System.lineSeparator();
	}

	private static Map<Integer, String> createDefaultPalette() {
		Map<Integer, String> map = new LinkedHashMap<>();
		map.put(1, "minecraft:grass_block");
		map.put(2, "minecraft:sandstone");
		map.put(3, "minecraft:mushroom_stem");
		map.put(4, "minecraft:redstone_block");
		map.put(5, "minecraft:packed_ice");
		map.put(6, "minecraft:iron_block");
		// 色ID 7 は葉ブロックと草花にしか無い。枯れないよう persistent=true にしておく。
		map.put(7, "minecraft:oak_leaves[persistent=true]");
		map.put(8, "minecraft:white_wool");
		map.put(9, "minecraft:clay");
		map.put(10, "minecraft:dirt");
		map.put(11, "minecraft:stone");
		// 色ID 12 は水系のブロックにしか無い。階段状の造形には向かない。
		map.put(12, "minecraft:water");
		map.put(13, "minecraft:oak_planks");
		map.put(14, "minecraft:diorite");
		map.put(15, "minecraft:orange_wool");
		map.put(16, "minecraft:magenta_wool");
		map.put(17, "minecraft:light_blue_wool");
		map.put(18, "minecraft:yellow_wool");
		map.put(19, "minecraft:lime_wool");
		map.put(20, "minecraft:pink_wool");
		map.put(21, "minecraft:gray_wool");
		map.put(22, "minecraft:light_gray_wool");
		map.put(23, "minecraft:cyan_wool");
		map.put(24, "minecraft:purple_wool");
		map.put(25, "minecraft:blue_wool");
		map.put(26, "minecraft:brown_wool");
		map.put(27, "minecraft:green_wool");
		map.put(28, "minecraft:red_wool");
		map.put(29, "minecraft:black_wool");
		map.put(30, "minecraft:gold_block");
		map.put(31, "minecraft:diamond_block");
		map.put(32, "minecraft:lapis_block");
		map.put(33, "minecraft:emerald_block");
		map.put(34, "minecraft:spruce_planks");
		map.put(35, "minecraft:netherrack");
		map.put(36, "minecraft:white_terracotta");
		map.put(37, "minecraft:orange_terracotta");
		map.put(38, "minecraft:magenta_terracotta");
		map.put(39, "minecraft:light_blue_terracotta");
		map.put(40, "minecraft:yellow_terracotta");
		map.put(41, "minecraft:lime_terracotta");
		map.put(42, "minecraft:pink_terracotta");
		map.put(43, "minecraft:gray_terracotta");
		map.put(44, "minecraft:light_gray_terracotta");
		map.put(45, "minecraft:cyan_terracotta");
		map.put(46, "minecraft:purple_terracotta");
		map.put(47, "minecraft:blue_terracotta");
		map.put(48, "minecraft:brown_terracotta");
		map.put(49, "minecraft:green_terracotta");
		map.put(50, "minecraft:red_terracotta");
		map.put(51, "minecraft:black_terracotta");
		map.put(52, "minecraft:crimson_nylium");
		map.put(53, "minecraft:crimson_planks");
		map.put(54, "minecraft:crimson_hyphae");
		map.put(55, "minecraft:warped_nylium");
		map.put(56, "minecraft:warped_planks");
		map.put(57, "minecraft:warped_hyphae");
		map.put(58, "minecraft:warped_wart_block");
		map.put(59, "minecraft:deepslate");
		map.put(60, "minecraft:raw_iron_block");
		map.put(61, "minecraft:verdant_froglight");
		return map;
	}
}
