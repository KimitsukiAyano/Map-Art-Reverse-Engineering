package com.example.rvmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.block.BlockState;

/**
 * colors(byte[16384])から、縮尺 1:1 のマップアート用の高さマップとブロック配置を作る。
 *
 * <p>アルゴリズム:
 * <ol>
 *   <li>各バイトを 基本色ID = v &gt;&gt; 2、明度 = v &amp; 3 に分解する。</li>
 *   <li>北端から南へ x 列ごとに独立に累積する。明度2 → 北隣 +1、明度1 → 北隣と同じ、
 *       明度0 → 北隣 -1。明度3(水深表現)が出たら中止。</li>
 *   <li>北端にヌーブライン 1 行を足すので、領域は X=128、Z=129(z=0 がヌーブライン)。</li>
 *   <li>各 x 列を独立に平行移動し、その列の最低高さが y=0 になるよう正規化する。
 *       明度は南北の相対差しか表さないため、列ごとの平行移動は像を変えない。</li>
 * </ol>
 *
 * <p>色のRGB変換・最近傍探索・ディザリングは一切行わない。基本色IDは確定値である。
 */
public final class SchematicBuilder {
	private SchematicBuilder() {
	}

	public record Placement(int x, int y, int z, BlockState state) {
	}

	public record Result(
			int sizeX,
			int sizeY,
			int sizeZ,
			List<Placement> placements,
			/** ブロックステートの表示名 → 個数。建材の必要量として使える。 */
			Map<String, Integer> blockCounts,
			/** 正規化後の最大 y。 */
			int maxY,
			/** 最も高くなった列の x 座標。 */
			int tallestColumnX,
			/** 基本色ID 0(未探索/透明)のピクセル数。 */
			int transparentPixels) {
	}

	/** 明度3(水深表現)が見つかったときに投げる。 */
	public static final class UnsupportedShadeException extends Exception {
		private final int x;
		private final int z;

		public UnsupportedShadeException(int x, int z) {
			super("明度3");
			this.x = x;
			this.z = z;
		}

		public int pixelX() {
			return x;
		}

		/** 画像内の z(0..127)。 */
		public int pixelZ() {
			return z;
		}
	}

	/** パレットに載っていない基本色IDが地図に出現したときに投げる。 */
	public static final class UndefinedColorException extends Exception {
		private final List<Integer> colorIds;

		public UndefinedColorException(List<Integer> colorIds) {
			super("未定義の基本色ID: " + colorIds);
			this.colorIds = colorIds;
		}

		public List<Integer> colorIds() {
			return colorIds;
		}
	}

	public static Result build(byte[] colors, PaletteConfig palette)
			throws UnsupportedShadeException, UndefinedColorException {
		final int width = Rvmap.MAP_SIZE;
		final int depth = width + 1; // ヌーブライン 1 行を北端に足す

		// heights[x][z]。z=0 がヌーブライン、z=1..128 が画像の 0..127 行目。
		int[][] heights = new int[width][depth];
		TreeSet<Integer> undefined = new TreeSet<>();
		int transparentPixels = 0;

		for (int x = 0; x < width; x++) {
			heights[x][0] = 0;

			for (int z = 1; z < depth; z++) {
				byte packed = colors[x + (z - 1) * width];
				int colorId = MapColors.baseColorId(packed);
				int shade = MapColors.shade(packed);

				if (colorId == 0) {
					// 未探索ピクセル。ブロックを置けないので高さは据え置く。
					transparentPixels++;
					heights[x][z] = heights[x][z - 1];
					continue;
				}

				if (shade == 3) {
					throw new UnsupportedShadeException(x, z - 1);
				}

				if (!palette.blocks().containsKey(colorId)) {
					undefined.add(colorId);
				}

				heights[x][z] = switch (shade) {
					case 2 -> heights[x][z - 1] + 1;
					case 1 -> heights[x][z - 1];
					default -> heights[x][z - 1] - 1;
				};
			}
		}

		// 対応するブロックが無い色が 1 つでもあれば、その色のピクセルを置けない。
		// 像が崩れるだけなので、ここで中止して色IDを報告する。
		if (!undefined.isEmpty()) {
			throw new UndefinedColorException(List.copyOf(undefined));
		}

		// 列ごとに平行移動して最低高さを 0 に合わせる。
		int maxY = 0;
		int tallestColumnX = 0;

		for (int x = 0; x < width; x++) {
			int min = heights[x][0];

			for (int z = 1; z < depth; z++) {
				min = Math.min(min, heights[x][z]);
			}

			int columnMax = 0;

			for (int z = 0; z < depth; z++) {
				heights[x][z] -= min;
				columnMax = Math.max(columnMax, heights[x][z]);
			}

			if (columnMax > maxY) {
				maxY = columnMax;
				tallestColumnX = x;
			}
		}

		List<Placement> placements = new ArrayList<>(width * depth);
		Map<String, Integer> counts = new LinkedHashMap<>();

		for (int x = 0; x < width; x++) {
			BlockState noob = palette.noobLine();
			placements.add(new Placement(x, heights[x][0], 0, noob));
			count(counts, noob);

			for (int z = 1; z < depth; z++) {
				byte packed = colors[x + (z - 1) * width];
				int colorId = MapColors.baseColorId(packed);

				if (colorId == 0) {
					continue; // 空気
				}

				// 未定義の色は上でまとめて弾いているので、ここに来る色は必ず対応表にある。
				BlockState state = palette.blocks().get(colorId);
				placements.add(new Placement(x, heights[x][z], z, state));
				count(counts, state);
			}
		}

		return new Result(
				width,
				maxY + 1,
				depth,
				placements,
				counts,
				maxY,
				tallestColumnX,
				transparentPixels);
	}

	private static void count(Map<String, Integer> counts, BlockState state) {
		counts.merge(StructureNbtWriter.describe(state), 1, Integer::sum);
	}
}
