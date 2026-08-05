package rvmaptools;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.util.math.BlockPos;

/**
 * Development-only probe. Not shipped in the mod jar.
 * 1) Prints the raw int returned by MapColor.getRenderColor(packed) so the byte order can be confirmed.
 * 2) Renders a swatch PNG (one row per base colour id, one column per brightness) for visual inspection.
 * 3) Dumps every block in the registry grouped by MapColor id so a correct default palette can be built.
 */
public final class ColorProbe {
	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.createGameVersion();
		net.minecraft.Bootstrap.initialize();

		System.out.println("WORLD_VERSION=" + net.minecraft.SharedConstants.getGameVersion().dataVersion().id());

		// --- 1) byte order -------------------------------------------------
		// PALE_GREEN is id 1 (grass). Its MapColor.color field is 0xRRGGBB.
		for (int id : new int[] {1, 4, 12, 14, 28, 29}) {
			MapColor mc = MapColor.get(id);
			int packed = id * 4 + 2; // NORMAL brightness
			int render = MapColor.getRenderColor(packed);
			System.out.printf("id=%2d color(RGB)=0x%06X getRenderColor(%3d)=0x%08X%n", id, mc.color, packed, render);
		}

		// --- 2) swatch PNG -------------------------------------------------
		// Uses the mod's own conversion, so the picture is proof of what the mod writes.
		int cell = 16;
		int rows = 64;
		BufferedImage img = new BufferedImage(4 * cell, rows * cell, BufferedImage.TYPE_INT_ARGB);
		for (int id = 0; id < rows; id++) {
			for (int shade = 0; shade < 4; shade++) {
				int argb = com.example.rvmap.MapColors.toArgb((byte) (id * 4 + shade));
				for (int dy = 0; dy < cell; dy++) {
					for (int dx = 0; dx < cell; dx++) {
						img.setRGB(shade * cell + dx, id * cell + dy, argb);
					}
				}
			}
		}
		File outDir = new File(args.length > 0 ? args[0] : ".");
		outDir.mkdirs();
		File out = new File(outDir, "swatch.png");
		ImageIO.write(img, "PNG", out);
		System.out.println("wrote " + out.getAbsolutePath());

		// --- 3) block -> MapColor id dump ----------------------------------
		StringBuilder sb = new StringBuilder();
		for (Block block : Registries.BLOCK) {
			BlockState state = block.getDefaultState();
			MapColor mc;
			try {
				mc = state.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
			} catch (Throwable t) {
				continue;
			}
			if (mc == null) continue;
			sb.append(mc.id).append('\t')
			  .append(Registries.BLOCK.getId(block)).append('\t')
			  .append(state.isOpaqueFullCube()).append('\t')
			  .append(String.format("0x%06X", mc.color)).append('\n');
		}
		java.nio.file.Files.writeString(new File(outDir, "blocks_by_mapcolor.tsv").toPath(), sb.toString());
		System.out.println("wrote blocks_by_mapcolor.tsv");

		// --- 4) what the shipped block list resolves to ----------------------
		System.out.println();
		System.out.println("--- default block list -> colour id ---");
		com.example.rvmap.PaletteConfig.GenerationResult generated = com.example.rvmap.PaletteConfig.generate();

		for (com.example.rvmap.PaletteConfig.Assignment assignment : generated.assignments()) {
			System.out.println("  " + com.example.rvmap.PaletteReport.line(assignment));
		}

		System.out.println("resolved colours: " + generated.palette().size()
				+ " / unused: " + generated.unused()
				+ " / missing 1..61: " + generated.missingColorIds());
	}
}
