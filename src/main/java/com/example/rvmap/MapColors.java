package com.example.rvmap;

import net.minecraft.block.MapColor;

/**
 * {@code MapState.colors} のパック値(基本色ID * 4 + 明度インデックス)を扱うユーティリティ。
 *
 * <p>色テーブルは一切ハードコードせず、バニラの {@link MapColor#getRenderColor(int)} を使う。
 *
 * <p><b>バイト順について。</b> 1.21.1 の {@code MapColor#getRenderColor(Brightness)} は
 * <pre>
 *   return 0xFF000000 | (b &lt;&lt; 16) | (g &lt;&lt; 8) | r;
 * </pre>
 * を返す。つまり戻り値は <b>ABGR</b>(最下位バイトが赤)で、NativeImage が期待する並びである。
 * BufferedImage の {@code TYPE_INT_ARGB} は ARGB なので、赤と青を入れ替える必要がある。
 * 実際に 1.21.1 のマッピング済み jar 上で確認した値:
 * <pre>
 *   id= 4 (BRIGHT_RED)  color=0xFF0000  getRenderColor(18) = 0xFF0000FF
 *   id=12 (WATER_BLUE)  color=0x4040FF  getRenderColor(50) = 0xFFFF4040
 * </pre>
 */
public final class MapColors {
	private MapColors() {
	}

	/** 基本色ID。0 は未探索(透明)。 */
	public static int baseColorId(byte packed) {
		return (packed & 0xFF) >> 2;
	}

	/** 明度インデックス 0..3。 */
	public static int shade(byte packed) {
		return packed & 3;
	}

	/**
	 * パック値を BufferedImage(TYPE_INT_ARGB)用の ARGB に変換する。
	 * 基本色ID 0(未探索)はアルファ 0 を返す。
	 */
	public static int toArgb(byte packed) {
		if (baseColorId(packed) == 0) {
			return 0;
		}

		int abgr = MapColor.getRenderColor(packed & 0xFF);

		int a = (abgr >>> 24) & 0xFF;
		int b = (abgr >> 16) & 0xFF;
		int g = (abgr >> 8) & 0xFF;
		int r = abgr & 0xFF;

		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
