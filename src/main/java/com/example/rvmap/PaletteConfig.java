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
import java.util.TreeSet;

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
 * {@code rvmap/palette.json} の生成・読み込み・検証。
 *
 * <p><b>色IDの対応表はハードコードしない。</b> MOD が持っているのは {@link #DEFAULT_BLOCKS} の
 * ブロックID一覧だけで、「どのブロックがどの色IDか」は初回起動時にゲームのレジストリへ
 * 問い合わせて決める。バージョン間で MapColor の割り当てが変わっても、こちらを直す必要がない。
 *
 * <p>生成されるファイルの形式は「基本色ID(文字列の数字) → ブロックID」。
 * それに加えて特別なキーを 3 つ持つ:
 * <ul>
 *   <li>{@code noob_line} … ヌーブラインに使うブロック(既定 {@code minecraft:stone})</li>
 *   <li>{@code palette_unused} … 色IDが衝突して不採用になったブロック(ブロックID → 色ID)</li>
 *   <li>{@code _} で始まるキー … コメント扱いで無視する</li>
 * </ul>
 */
public final class PaletteConfig {
	public static final String FILE_NAME = "palette.json";
	public static final String NOOB_LINE_KEY = "noob_line";
	public static final String UNUSED_KEY = "palette_unused";
	public static final String DEFAULT_NOOB_LINE = "minecraft:stone";

	/** MapColor の ID は パック値の上位 6 bit なので 0〜63。 */
	public static final int MAX_COLOR_ID = 63;

	/** 埋まっていてほしい色IDの範囲。ここに穴があれば警告する。 */
	public static final int EXPECTED_MIN_COLOR_ID = 1;
	public static final int EXPECTED_MAX_COLOR_ID = 61;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 既定のブロックID一覧。ここに色IDは書かない。並び順が優先順位で、
	 * 同じ色IDに複数が当たった場合は先に来たほうを採用する。
	 */
	public static final List<String> DEFAULT_BLOCKS = List.of(
			"minecraft:mushroom_stem",
			"minecraft:bamboo_block",
			"minecraft:oak_planks",
			"minecraft:crimson_planks",
			"minecraft:crimson_hyphae",
			"minecraft:warped_nylium",
			"minecraft:warped_hyphae",
			"minecraft:warped_wart_block",
			"minecraft:netherrack",
			"minecraft:waxed_oxidized_copper",
			"minecraft:raw_iron_block",
			"minecraft:iron_block",
			"minecraft:lapis_block",
			"minecraft:emerald_block",
			"minecraft:gold_block",
			"minecraft:black_wool",
			"minecraft:gray_wool",
			"minecraft:light_gray_wool",
			"minecraft:brown_wool",
			"minecraft:pink_wool",
			"minecraft:magenta_wool",
			"minecraft:purple_wool",
			"minecraft:blue_wool",
			"minecraft:cyan_wool",
			"minecraft:light_blue_wool",
			"minecraft:green_wool",
			"minecraft:lime_wool",
			"minecraft:yellow_wool",
			"minecraft:orange_wool",
			"minecraft:red_wool",
			"minecraft:white_wool",
			"minecraft:black_terracotta",
			"minecraft:gray_terracotta",
			"minecraft:light_gray_terracotta",
			"minecraft:brown_terracotta",
			"minecraft:pink_terracotta",
			"minecraft:magenta_terracotta",
			"minecraft:purple_terracotta",
			"minecraft:blue_terracotta",
			"minecraft:cyan_terracotta",
			"minecraft:light_blue_terracotta",
			"minecraft:green_terracotta",
			"minecraft:lime_terracotta",
			"minecraft:yellow_terracotta",
			"minecraft:orange_terracotta",
			"minecraft:red_terracotta",
			"minecraft:white_terracotta",
			"minecraft:terracotta",
			"minecraft:slime_block",
			"minecraft:podzol",
			"minecraft:clay",
			"minecraft:glow_lichen",
			"minecraft:stone",
			"minecraft:deepslate",
			"minecraft:granite",
			"minecraft:diorite",
			"minecraft:prismarine_bricks",
			"minecraft:sandstone",
			"minecraft:packed_ice",
			"minecraft:crimson_nylium",
			"minecraft:tnt");

	private final Map<Integer, BlockState> blocks;
	private final BlockState noobLine;
	private final Map<String, Integer> unused;

	private PaletteConfig(Map<Integer, BlockState> blocks, BlockState noobLine, Map<String, Integer> unused) {
		this.blocks = blocks;
		this.noobLine = noobLine;
		this.unused = unused;
	}

	public Map<Integer, BlockState> blocks() {
		return Collections.unmodifiableMap(blocks);
	}

	public BlockState noobLine() {
		return noobLine;
	}

	/** 色IDが衝突して不採用になったブロック(ブロックID → 実際の色ID)。 */
	public Map<String, Integer> unused() {
		return Collections.unmodifiableMap(unused);
	}

	// ------------------------------------------------------------- 自動生成

	/** 1 ブロック分の割り当て結果。 */
	public record Assignment(String blockId, int colorId, boolean accepted, String collidesWith, String error) {
		public boolean failed() {
			return error != null;
		}
	}

	/** {@link #generate()} の結果。 */
	public record GenerationResult(
			/** 採用された 色ID → ブロックID。 */
			Map<Integer, String> palette,
			/** 全ブロックの割り当てログ(リスト順)。 */
			List<Assignment> assignments,
			/** 衝突して退避されたブロックID → 色ID。 */
			Map<String, Integer> unused,
			/** 1〜61 のうち埋まらなかった色ID。 */
			List<Integer> missingColorIds) {
	}

	/**
	 * {@link #DEFAULT_BLOCKS} をレジストリへ問い合わせて 色ID → ブロック の対応表を作る。
	 * ブロックレジストリを参照するので、呼び出しはゲームスレッドから行うこと。
	 */
	public static GenerationResult generate() {
		Map<Integer, String> palette = new TreeMap<>();
		List<Assignment> assignments = new ArrayList<>(DEFAULT_BLOCKS.size());
		Map<String, Integer> unused = new LinkedHashMap<>();

		for (String blockId : DEFAULT_BLOCKS) {
			BlockState state;

			try {
				state = BlockArgumentParser.block(Registries.BLOCK, blockId, false).blockState();
			} catch (CommandSyntaxException e) {
				assignments.add(new Assignment(blockId, -1, false, null, e.getMessage()));
				continue;
			}

			int colorId = mapColorId(state);

			if (colorId == 0) {
				assignments.add(new Assignment(blockId, 0, false, null,
						"MapColor が透明(色ID 0)なので地図に描かれません"));
				continue;
			}

			String existing = palette.get(colorId);

			if (existing != null) {
				// 先に来たほうを採用し、後のものは退避する。
				assignments.add(new Assignment(blockId, colorId, false, existing, null));
				unused.put(blockId, colorId);
				continue;
			}

			palette.put(colorId, blockId);
			assignments.add(new Assignment(blockId, colorId, true, null, null));
		}

		List<Integer> missing = new ArrayList<>();

		for (int colorId = EXPECTED_MIN_COLOR_ID; colorId <= EXPECTED_MAX_COLOR_ID; colorId++) {
			if (!palette.containsKey(colorId)) {
				missing.add(colorId);
			}
		}

		return new GenerationResult(palette, assignments, unused, List.copyOf(missing));
	}

	public static String toJson(GenerationResult generated) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("_comment", "基本色ID -> ブロックID。初回起動時にゲームのレジストリから自動生成されます。");
		out.put("_comment2", "手で編集しても構いませんが、/rvmap build のたびにレジストリと照合され、"
				+ "色IDが一致しないと生成を中止します。");
		out.put("_comment3", "\"" + NOOB_LINE_KEY + "\" は最北端に足す 1 行(ヌーブライン)のブロックです。");
		out.put("_comment4", "\"" + UNUSED_KEY + "\" は色IDが衝突して不採用になったブロックの控えです(読み込み時は無視されます)。");
		out.put(NOOB_LINE_KEY, DEFAULT_NOOB_LINE);

		for (Map.Entry<Integer, String> entry : generated.palette().entrySet()) {
			out.put(String.valueOf(entry.getKey()), entry.getValue());
		}

		out.put(UNUSED_KEY, generated.unused());

		return GSON.toJson(out) + System.lineSeparator();
	}

	/** {@link #DEFAULT_BLOCKS} から生成し直して palette.json を上書きする。 */
	public static GenerationResult regenerate() throws IOException {
		GenerationResult generated = generate();
		Files.writeString(file(), toJson(generated), StandardCharsets.UTF_8);
		return generated;
	}

	// --------------------------------------------------------- 読み込み・検証

	/** 読み込み結果。エラーが 1 つでもあれば {@code config} は null。 */
	public record LoadResult(PaletteConfig config, List<String> errors, GenerationResult generated) {
		public boolean ok() {
			return config != null;
		}

		/** このロードで palette.json を新規生成したか。 */
		public boolean created() {
			return generated != null;
		}
	}

	public static Path file() throws IOException {
		return RvmapPaths.outputDirectory().resolve(FILE_NAME);
	}

	/**
	 * palette.json を読み込む。存在しなければレジストリから生成する。
	 * ブロックレジストリを参照するので、呼び出しはゲームスレッドから行うこと。
	 */
	public static LoadResult load() {
		Path file;

		try {
			file = file();
		} catch (IOException e) {
			return new LoadResult(null, List.of("rvmap フォルダを作成できませんでした: " + e.getMessage()), null);
		}

		GenerationResult generated = null;

		if (!Files.exists(file)) {
			try {
				generated = regenerate();
			} catch (IOException e) {
				return new LoadResult(null, List.of(FILE_NAME + " を作成できませんでした: " + e.getMessage()), null);
			}
		}

		String json;

		try {
			json = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return new LoadResult(null, List.of(FILE_NAME + " を読み込めませんでした: " + e.getMessage()), generated);
		}

		return validate(json, generated);
	}

	/**
	 * palette.json の中身を検証する。手で編集されていても必ずここを通す。
	 * ブロックレジストリを参照するので、呼び出しはゲームスレッドから行うこと。
	 */
	public static LoadResult validate(String json, GenerationResult generated) {
		JsonObject root;

		try {
			JsonElement parsed = JsonParser.parseString(json);

			if (!parsed.isJsonObject()) {
				return new LoadResult(null, List.of(FILE_NAME + " のトップレベルが JSON オブジェクトではありません。"), generated);
			}

			root = parsed.getAsJsonObject();
		} catch (JsonSyntaxException e) {
			return new LoadResult(null, List.of(FILE_NAME + " の JSON を解釈できませんでした: " + e.getMessage()), generated);
		}

		return parse(root, generated);
	}

	private static LoadResult parse(JsonObject root, GenerationResult generated) {
		List<String> errors = new ArrayList<>();
		Map<Integer, BlockState> blocks = new TreeMap<>();
		Map<String, Integer> unused = new LinkedHashMap<>();
		BlockState noobLine = null;

		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			String key = entry.getKey();

			if (key.startsWith("_")) {
				continue;
			}

			if (UNUSED_KEY.equals(key)) {
				readUnused(entry.getValue(), unused);
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

			// 手編集されている可能性があるので、必ずレジストリと照合する。
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
			return new LoadResult(null, errors, generated);
		}

		return new LoadResult(new PaletteConfig(blocks, noobLine, unused), List.of(), generated);
	}

	private static void readUnused(JsonElement element, Map<String, Integer> out) {
		if (!element.isJsonObject()) {
			return; // 控えなので、壊れていても読み込みは止めない。
		}

		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			try {
				out.put(entry.getKey(), entry.getValue().getAsInt());
			} catch (RuntimeException e) {
				// 無視する。
			}
		}
	}

	private static BlockState parseBlockState(String blockString, List<String> errors, String label) {
		try {
			// 1.21.11 では Registry 自体が RegistryWrapper.Impl なので、そのまま渡せる。
			return BlockArgumentParser.block(Registries.BLOCK, blockString, false).blockState();
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

	/** 色IDの見た目を "#RRGGBB" で返す。エラーメッセージ用。 */
	public static String describeColor(int colorId) {
		if (colorId == 0) {
			return "透明/未探索";
		}

		try {
			return String.format("#%06X", MapColor.get(colorId).color);
		} catch (RuntimeException e) {
			// MapColor.get は範囲外の ID で例外を投げる。メッセージの組み立てで落ちないようにする。
			return "未使用の色ID";
		}
	}

	/** 読み込み済みの対応表のうち、1〜61 で埋まっていない色ID。 */
	public List<Integer> missingColorIds() {
		List<Integer> missing = new ArrayList<>();

		for (int colorId = EXPECTED_MIN_COLOR_ID; colorId <= EXPECTED_MAX_COLOR_ID; colorId++) {
			if (!blocks.containsKey(colorId)) {
				missing.add(colorId);
			}
		}

		return missing;
	}

	/** 色ID順に "色ID -> ブロック" を並べたもの。{@code /rvmap palette} の表示用。 */
	public List<String> describeEntries() {
		List<String> lines = new ArrayList<>();

		for (Map.Entry<Integer, BlockState> entry : new TreeMap<>(blocks).entrySet()) {
			lines.add(String.format("%2d %s  %s",
					entry.getKey(),
					describeColor(entry.getKey()),
					StructureNbtWriter.describe(entry.getValue())));
		}

		return lines;
	}

	/** 衝突していない前提で、対応表に載っている色IDの集合。 */
	public TreeSet<Integer> colorIds() {
		return new TreeSet<>(blocks.keySet());
	}
}
