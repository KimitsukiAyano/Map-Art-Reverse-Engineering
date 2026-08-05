package com.example.rvmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public final class MapImageWriter {
	private MapImageWriter() {
	}

	/** colors(byte[16384])を 128x128 の ARGB 画像にする。 */
	public static BufferedImage toImage(byte[] colors) {
		int size = Rvmap.MAP_SIZE;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				image.setRGB(x, z, MapColors.toArgb(colors[x + z * size]));
			}
		}

		return image;
	}

	public static void writePng(byte[] colors, Path destination) throws IOException {
		if (!ImageIO.write(toImage(colors), "PNG", destination.toFile())) {
			throw new IOException("PNG エンコーダが見つかりませんでした");
		}
	}
}
