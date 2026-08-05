package rvmaptools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.rvmap.MapColors;
import com.example.rvmap.MapImageWriter;
import com.example.rvmap.PaletteConfig;
import com.example.rvmap.SchematicBuilder;
import com.example.rvmap.StructureNbtWriter;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;

/**
 * Development-only smoke test. Not shipped in the mod jar.
 * Exercises the palette -> height map -> vanilla structure NBT pipeline against real Minecraft classes.
 */
public final class SchematicSmokeTest {
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.createGameVersion();
		net.minecraft.Bootstrap.initialize();

		Path outDir = Path.of(args.length > 0 ? args[0] : "build/probe");
		Files.createDirectories(outDir);

		// --- every default palette entry must actually carry the colour id it is filed under ---
		Map<Integer, BlockState> states = new LinkedHashMap<>();

		for (Map.Entry<Integer, String> entry : PaletteConfig.defaultPalette().entrySet()) {
			BlockState state = BlockArgumentParser
					.block(Registries.BLOCK.getReadOnlyWrapper(), entry.getValue(), false)
					.blockState();
			int actual = PaletteConfig.mapColorId(state);
			check(actual == entry.getKey(),
					"default palette " + entry.getKey() + " -> " + entry.getValue() + " (actual id " + actual + ")");
			states.put(entry.getKey(), state);
		}

		BlockState noob = BlockArgumentParser
				.block(Registries.BLOCK.getReadOnlyWrapper(), PaletteConfig.DEFAULT_NOOB_LINE, false)
				.blockState();
		PaletteConfig palette = PaletteConfig.of(states, noob);

		// --- height maps ---------------------------------------------------
		// column 0: all shade 2 (staircase up going south) -> heights 0..128
		// column 1: all shade 1 (flat)                     -> heights all 0
		// column 2: all shade 0 (staircase down)           -> normalised to 128..0
		// column 3: alternating 2/0                        -> saw tooth
		byte[] colors = new byte[128 * 128];
		int white = 8; // white_wool

		for (int z = 0; z < 128; z++) {
			for (int x = 0; x < 128; x++) {
				int shade = switch (x) {
					case 0 -> 2;
					case 1 -> 1;
					case 2 -> 0;
					case 3 -> (z % 2 == 0) ? 2 : 0;
					default -> 1;
				};
				colors[x + z * 128] = (byte) (white * 4 + shade);
			}
		}

		SchematicBuilder.Result result = SchematicBuilder.build(colors, palette);

		check(result.sizeX() == 128, "sizeX == 128 (was " + result.sizeX() + ")");
		check(result.sizeZ() == 129, "sizeZ == 129, noob line included (was " + result.sizeZ() + ")");
		check(result.maxY() == 128, "tallest column reaches y=128 (was " + result.maxY() + ")");
		check(result.sizeY() == 129, "sizeY == maxY + 1 (was " + result.sizeY() + ")");
		check(result.tallestColumnX() == 0 || result.tallestColumnX() == 2,
				"tallest column is one of the full staircases (was x=" + result.tallestColumnX() + ")");
		check(result.transparentPixels() == 0, "no transparent pixels");
		check(result.undefinedColorIds().isEmpty(), "no undefined colour ids");

		// 128*129 placements: every pixel plus the noob line, nothing dropped
		check(result.placements().size() == 128 * 129,
				"one block per cell (was " + result.placements().size() + ")");

		Map<String, Integer> byColumn = new LinkedHashMap<>();
		int[] col0 = new int[129];
		int[] col1 = new int[129];
		int[] col2 = new int[129];

		for (SchematicBuilder.Placement p : result.placements()) {
			if (p.x() == 0) col0[p.z()] = p.y();
			if (p.x() == 1) col1[p.z()] = p.y();
			if (p.x() == 2) col2[p.z()] = p.y();
		}

		check(col0[0] == 0 && col0[128] == 128, "shade-2 column climbs 0 -> 128 going south");
		check(col1[0] == 0 && col1[128] == 0, "shade-1 column stays flat");
		check(col2[0] == 128 && col2[128] == 0, "shade-0 column normalised to 128 -> 0");
		byColumn.clear();

		// noob line must exist at every x, at z = 0
		long noobCount = result.placements().stream().filter(p -> p.z() == 0).count();
		check(noobCount == 128, "noob line spans the whole width (was " + noobCount + ")");
		check(result.blockCounts().getOrDefault("minecraft:stone", 0) == 128,
				"noob line counted as 128 stone");

		// --- shade 3 must abort --------------------------------------------
		byte[] withDeepWater = colors.clone();
		withDeepWater[5 + 7 * 128] = (byte) (12 * 4 + 3);
		boolean aborted = false;

		try {
			SchematicBuilder.build(withDeepWater, palette);
		} catch (SchematicBuilder.UnsupportedShadeException e) {
			aborted = e.pixelX() == 5 && e.pixelZ() == 7;
		}

		check(aborted, "shade 3 aborts and reports the pixel");

		// --- transparent + undefined reporting ------------------------------
		byte[] mixed = colors.clone();
		mixed[10 + 10 * 128] = 0;                    // colour id 0 -> air
		mixed[11 + 10 * 128] = (byte) (3 * 4 + 1);   // mushroom_stem, present in default palette
		Map<Integer, BlockState> partial = new LinkedHashMap<>(states);
		partial.remove(3);
		SchematicBuilder.Result mixedResult =
				SchematicBuilder.build(mixed, PaletteConfig.of(partial, noob));
		check(mixedResult.transparentPixels() == 1, "one transparent pixel counted");
		check(mixedResult.undefinedColorIds().equals(java.util.List.of(3)),
				"undefined colour id 3 reported (was " + mixedResult.undefinedColorIds() + ")");
		check(mixedResult.placements().size() == 128 * 129 - 2,
				"air and undefined cells produce no block (was " + mixedResult.placements().size() + ")");

		// --- write the structure and read it back ---------------------------
		Path nbtFile = outDir.resolve("smoketest.nbt");
		StructureNbtWriter.write(result, nbtFile);
		NbtCompound root = NbtIo.readCompressed(nbtFile, NbtSizeTracker.ofUnlimitedBytes());

		NbtList size = root.getList("size", NbtElement.INT_TYPE);
		check(size.size() == 3, "size is a 3-element int list");
		check(size.getInt(0) == 128 && size.getInt(1) == 129 && size.getInt(2) == 129,
				"size == [128, 129, 129] (was [" + size.getInt(0) + ", " + size.getInt(1) + ", " + size.getInt(2) + "])");

		NbtList paletteList = root.getList("palette", NbtElement.COMPOUND_TYPE);
		check(paletteList.size() == 2, "palette holds only the blocks actually used (was " + paletteList.size() + ")");
		check(root.contains("DataVersion", NbtElement.INT_TYPE), "DataVersion present");
		check(root.getInt("DataVersion") == net.minecraft.SharedConstants.getGameVersion().getSaveVersion().getId(),
				"DataVersion matches the running game");
		check(root.contains("entities", NbtElement.LIST_TYPE), "entities list present");

		NbtList blocks = root.getList("blocks", NbtElement.COMPOUND_TYPE);
		check(blocks.size() == 128 * 129, "one blocks entry per placement (was " + blocks.size() + ")");

		NbtCompound first = blocks.getCompound(0);
		check(first.getList("pos", NbtElement.INT_TYPE).size() == 3, "block pos is a 3-element int list");
		check(first.contains("state", NbtElement.INT_TYPE), "block state index present");

		// every state index must be inside the palette
		boolean indicesOk = true;

		for (int i = 0; i < blocks.size(); i++) {
			int state = blocks.getCompound(i).getInt("state");

			if (state < 0 || state >= paletteList.size()) {
				indicesOk = false;
				break;
			}
		}

		check(indicesOk, "every state index is inside the palette");

		// palette entries must round-trip through NbtHelper
		String firstName = paletteList.getCompound(0).getString("Name");
		check(firstName.startsWith("minecraft:"), "palette entry carries a namespaced Name (was " + firstName + ")");

		System.out.println("gzip-compressed: " + isGzip(nbtFile));
		check(isGzip(nbtFile), "output is gzip compressed");

		// The strongest compatibility check available offline: hand the file back to
		// vanilla's own structure reader. Litematica reads the same schema.
		net.minecraft.structure.StructureTemplate template = new net.minecraft.structure.StructureTemplate();
		template.readNbt(Registries.BLOCK.getReadOnlyWrapper(), root);
		net.minecraft.util.math.Vec3i templateSize = template.getSize();
		check(templateSize.getX() == 128 && templateSize.getY() == 129 && templateSize.getZ() == 129,
				"vanilla StructureTemplate reads it back with size " + templateSize);

		// --- PNG round trip --------------------------------------------------
		byte[] colourful = new byte[128 * 128];

		for (int z = 0; z < 128; z++) {
			for (int x = 0; x < 128; x++) {
				int id = (z < 32) ? 4 : (z < 64) ? 12 : (z < 96) ? 8 : 0; // red, blue, white, transparent
				colourful[x + z * 128] = (byte) (id * 4 + 2);
			}
		}

		Path png = outDir.resolve("smoketest.png");
		MapImageWriter.writePng(colourful, png);
		java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png.toFile());
		check(image.getRGB(0, 0) == 0xFFFF0000, String.format("red row is 0xFFFF0000 (was 0x%08X)", image.getRGB(0, 0)));
		check(image.getRGB(0, 40) == 0xFF4040FF, String.format("blue row is 0xFF4040FF (was 0x%08X)", image.getRGB(0, 40)));
		check((image.getRGB(0, 120) >>> 24) == 0, "transparent row has alpha 0");
		check(MapColors.baseColorId((byte) (13 * 4 + 2)) == 13, "baseColorId unpacks correctly");
		check(MapColors.shade((byte) (13 * 4 + 2)) == 2, "shade unpacks correctly");

		// --- palette.json validation ----------------------------------------
		PaletteConfig.LoadResult good = PaletteConfig.validate(PaletteConfig.defaultJson(), false);
		check(good.ok(), "generated default palette.json validates: " + good.errors());
		check(good.config().blocks().size() == PaletteConfig.defaultPalette().size(),
				"all default entries survive validation");
		check(PaletteConfig.mapColorId(good.config().noobLine()) == 11, "default noob line is stone (colour id 11)");

		// sand is colour id 2, so filing it under 13 must be rejected with a concrete message
		PaletteConfig.LoadResult wrong =
				PaletteConfig.validate("{\"13\": \"minecraft:sand\"}", false);
		check(!wrong.ok(), "mismatched colour id is rejected");
		check(wrong.errors().size() == 1 && wrong.errors().get(0).contains("色ID 13")
						&& wrong.errors().get(0).contains("実際には色ID 2"),
				"mismatch message names both ids: " + wrong.errors());

		check(!PaletteConfig.validate("{\"13\": \"minecraft:not_a_block\"}", false).ok(),
				"unknown block id is rejected");
		check(!PaletteConfig.validate("{\"13\": \"minecraft:white_wool[nope=1]\"}", false).ok(),
				"unknown blockstate property is rejected");
		check(!PaletteConfig.validate("{\"999\": \"minecraft:white_wool\"}", false).ok(),
				"out-of-range colour id is rejected");
		check(!PaletteConfig.validate("{\"0\": \"minecraft:white_wool\"}", false).ok(),
				"colour id 0 is rejected");
		check(!PaletteConfig.validate("{\"abc\": \"minecraft:white_wool\"}", false).ok(),
				"non-numeric key is rejected");
		check(!PaletteConfig.validate("[]", false).ok(), "non-object json is rejected");
		check(PaletteConfig.validate("{\"_comment\": \"hi\", \"8\": \"minecraft:white_wool\"}", false).ok(),
				"underscore keys are treated as comments");

		PaletteConfig.LoadResult customNoob =
				PaletteConfig.validate("{\"noob_line\": \"minecraft:black_wool\"}", false);
		check(customNoob.ok() && PaletteConfig.mapColorId(customNoob.config().noobLine()) == 29,
				"noob_line is configurable");

		// --- filename sanitising ---------------------------------------------
		checkName("../../../etc/passwd", "passwd.png");
		checkName("..\\..\\windows\\system32\\evil", "evil.png");
		checkName("..", "FALLBACK.png");
		checkName("....", "FALLBACK.png");
		checkName("", "FALLBACK.png");
		checkName("   ", "FALLBACK.png");
		checkName("/", "FALLBACK.png");
		checkName("my map", "my_map.png");
		checkName("already.png", "already.png");
		checkName("a:b*c?d\"e<f>g|h", "a_b_c_d_e_f_g_h.png");
		checkName("マップアート", "マップアート.png");
		checkName("CON", "_CON.png");
		checkName(".hidden", "hidden.png");

		System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));

		if (failures > 0) {
			System.exit(1);
		}
	}

	private static boolean isGzip(Path file) throws Exception {
		byte[] head = new byte[2];

		try (var in = Files.newInputStream(file)) {
			return in.read(head) == 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
		}
	}

	private static void checkName(String raw, String expected) {
		String actual = com.example.rvmap.RvmapPaths.sanitizeFileName(raw, ".png", "FALLBACK.png");
		check(expected.equals(actual), "sanitize(\"" + raw + "\") == " + expected + " (was " + actual + ")");
	}

	private static void check(boolean condition, String what) {
		System.out.println((condition ? "  ok   " : "  FAIL ") + what);

		if (!condition) {
			failures++;
		}
	}
}
