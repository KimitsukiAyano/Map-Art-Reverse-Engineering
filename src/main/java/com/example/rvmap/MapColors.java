package com.example.rvmap;

import net.minecraft.block.MapColor;

/**
 * {@code MapState.colors} のパック値(基本色ID * 4 + 明度インデックス)を扱うユーティリティ。
 *
 * <p>色テーブルは一切ハードコードせず、バニラの {@link MapColor#getRenderColor(int)} を使う。
 *
 * <p><b>バイト順について。</b> ここは 1.21.1 から 1.21.11 の間で変わった箇所なので注意。
 * 1.21.1 の {@code MapColor#getRenderColor(Brightness)} は
 * <pre>
 *   return 0xFF000000 | (b &lt;&lt; 16) | (g &lt;&lt; 8) | r;   // ABGR(最下位バイトが赤)
 * </pre>
 * を返していたが、1.21.11 では {@code ColorHelper} 経由になり
 * <pre>
 *   return ColorHelper.scaleRgb(ColorHelper.fullAlpha(this.color), brightness.brightness);
 * </pre>
 * となった。{@code fullAlpha} は {@code color | 0xFF000000} で、{@code color} は 0xRRGGBB。
 * {@code scaleRgb} は {@code ColorHelper.getRed}(ビット 16〜23)などで分解して
 * {@code getArgb} で組み直すだけなので、<b>戻り値は ARGB</b> になる。
 * つまり <b>1.21.11 では赤と青の入れ替えは不要</b>。
 *
 * <p>1.21.11 のマッピング済み jar 上で実際に確認した値:
 * <pre>
 *   id= 4 (BRIGHT_RED)  color=0xFF0000  getRenderColor(18) = 0xFFFF0000
 *   id=12 (WATER_BLUE)  color=0x4040FF  getRenderColor(50) = 0xFF4040FF
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

		// 1.21.11 の getRenderColor は既に ARGB。入れ替えはしない。
		return MapColor.getRenderColor(packed & 0xFF);
	}
}
