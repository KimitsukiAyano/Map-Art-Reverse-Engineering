package com.example.rvmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Rvmap {
	public static final String MOD_ID = "rvmap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 額縁を探す距離。通常のリーチだと壁一面のマップアートに届かないため長めに取る。 */
	public static final double REACH = 32.0D;

	/** 地図の一辺のピクセル数。MapState.colors の長さは WIDTH * WIDTH。 */
	public static final int MAP_SIZE = 128;

	private Rvmap() {
	}
}
