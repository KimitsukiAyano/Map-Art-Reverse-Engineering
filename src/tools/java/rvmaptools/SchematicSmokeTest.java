package rvmaptools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.rvmap.MapColors;
import com.example.rvmap.MapImageWriter;
import com.example.rvmap.PaletteConfig;
import com.example.rvmap.SchematicBuilder;
import com.example.rvmap.StructureNbtWriter;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;

/**
 * Development-only smoke test. Not shipped in the mod jar.
 * Exercises palette generation -> validation -> height map -> vanilla structure NBT against real
 * Minecraft classes, then reads the result back through vanilla's own structure reader.
 */
public final class SchematicSmokeTest {
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.createGameVersion();
		net.minecraft.Bootstrap.initialize();

		Path outDir = Path.of(args.length > 0 ? args[0] : "build/probe");
		Files.createDirectories(outDir);

		System.out.println("game " + net.minecraft.SharedConstants.getGameVersion().name()
				+ " / DataVersion " + net.minecraft.SharedConstants.getGameVersion().dataVersion().id());

		// --- palette generation from the registry ----------------------------
		PaletteConfig.GenerationResult generated = PaletteConfig.generate();

		check(generated.assignments().size() == PaletteConfig.DEFAULT_BLOCKS.size(),
				"every shipped block id produced an assignment");
		check(generated.assignments().stream().noneMatch(PaletteConfig.Assignment::failed),
				"every shipped block id resolves in this version");

		for (PaletteConfig.Assignment assignment : generated.assignments()) {
			if (assignment.failed()) {
				continue;
			}

			BlockState state = parse(assignment.blockId());
			int actual = PaletteConfig.mapColorId(state);
			check(actual == assignment.colorId(),
					"assignment reports the registry's colour id for " + assignment.blockId()
							+ " (said " + assignment.colorId() + ", registry says " + actual + ")");

			if (assignment.accepted()) {
				check(assignment.blockId().equals(generated.palette().get(assignment.colorId())),
						assignment.blockId() + " owns colour id " + assignment.colorId());
				check(!generated.unused().containsKey(assignment.blockId()),
						assignment.blockId() + " is not parked in unused");
			} else {
				// collision: the winner must be the block already holding that colour
				check(assignment.collidesWith() != null
								&& assignment.collidesWith().equals(generated.palette().get(assignment.colorId())),
						"collision on colour id " + assignment.colorId() + " names the winner");
				check(generated.unused().containsKey(assignment.blockId()),
						assignment.blockId() + " is parked in unused");
			}
		}

		check(generated.palette().size() + generated.unused().size() == PaletteConfig.DEFAULT_BLOCKS.size(),
				"accepted + unused accounts for the whole list");

		for (int id = PaletteConfig.EXPECTED_MIN_COLOR_ID; id <= PaletteConfig.EXPECTED_MAX_COLOR_ID; id++) {
			boolean present = generated.palette().containsKey(id);
			boolean reportedMissing = generated.missingColorIds().contains(id);
			check(present != reportedMissing, "colour id " + id + " is either mapped or reported missing");
		}

		System.out.println("  -> " + generated.palette().size() + " colours mapped, "
				+ generated.unused().size() + " parked, missing " + generated.missingColorIds());

		// --- the generated json must survive its own validator ----------------
		PaletteConfig.LoadResult loaded = PaletteConfig.validate(PaletteConfig.toJson(generated), null);
		check(loaded.ok(), "generated palette.json validates: " + loaded.errors());
		PaletteConfig palette = loaded.config();
		check(palette.blocks().size() == generated.palette().size(), "all generated entries survive validation");
		check(palette.unused().equals(generated.unused()), "unused section round-trips");
		check(PaletteConfig.mapColorId(palette.noobLine()) == PaletteConfig.mapColorId(parse(PaletteConfig.DEFAULT_NOOB_LINE)),
				"default noob line round-trips");
		check(palette.missingColorIds().equals(generated.missingColorIds()),
				"loaded config reports the same missing ids");

		// pick a colour the generated palette actually covers, so the test does not
		// hardcode a colour id that could move between versions
		int paintId = palette.colorIds().first();
		String paintBlock = StructureNbtWriter.describe(palette.blocks().get(paintId));
		System.out.println("  using colour id " + paintId + " (" + paintBlock + ") for the height tests");

		// --- height maps ------------------------------------------------------
		// column 0: all shade 2 (staircase up going south) -> heights 0..128
		// column 1: all shade 1 (flat)                     -> heights all 0
		// column 2: all shade 0 (staircase down)           -> normalised to 128..0
		byte[] colors = new byte[128 * 128];

		for (int z = 0; z < 128; z++) {
			for (int x = 0; x < 128; x++) {
				int shade = switch (x) {
					case 0 -> 2;
					case 1 -> 1;
					case 2 -> 0;
					case 3 -> (z % 2 == 0) ? 2 : 0;
					default -> 1;
				};
				colors[x + z * 128] = (byte) (paintId * 4 + shade);
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
		check(result.placements().size() == 128 * 129,
				"one block per cell (was " + result.placements().size() + ")");

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

		long noobCount = result.placements().stream().filter(p -> p.z() == 0).count();
		check(noobCount == 128, "noob line spans the whole width (was " + noobCount + ")");
		check(result.blockCounts().getOrDefault(StructureNbtWriter.describe(palette.noobLine()), 0) >= 128,
				"noob line contributes 128 blocks to the material count");

		// --- shade 3 must abort ----------------------------------------------
		byte[] withDeepWater = colors.clone();
		withDeepWater[5 + 7 * 128] = (byte) (paintId * 4 + 3);
		boolean shadeAborted = false;

		try {
			SchematicBuilder.build(withDeepWater, palette);
		} catch (SchematicBuilder.UnsupportedShadeException e) {
			shadeAborted = e.pixelX() == 5 && e.pixelZ() == 7;
		}

		check(shadeAborted, "shade 3 aborts and reports the pixel");

		// --- an unmapped colour must abort and name the colour id --------------
		int spareId = palette.colorIds().last();
		check(spareId != paintId, "test needs two distinct mapped colours");

		String restrictedJson = "{\"" + PaletteConfig.NOOB_LINE_KEY + "\": \"" + PaletteConfig.DEFAULT_NOOB_LINE
				+ "\", \"" + paintId + "\": \"" + generated.palette().get(paintId) + "\"}";
		PaletteConfig restricted = PaletteConfig.validate(restrictedJson, null).config();
		check(restricted != null, "restricted palette parses");

		byte[] withUnmapped = colors.clone();
		withUnmapped[20 + 30 * 128] = (byte) (spareId * 4 + 1);
		List<Integer> reported = null;

		try {
			SchematicBuilder.build(withUnmapped, restricted);
		} catch (SchematicBuilder.UndefinedColorException e) {
			reported = e.colorIds();
		}

		check(reported != null && reported.equals(List.of(spareId)),
				"unmapped colour aborts the build and reports id " + spareId + " (was " + reported + ")");

		// --- transparent pixels are air, not an abort --------------------------
		byte[] withHole = colors.clone();
		withHole[10 + 10 * 128] = 0;
		SchematicBuilder.Result holed = SchematicBuilder.build(withHole, palette);
		check(holed.transparentPixels() == 1, "one transparent pixel counted");
		check(holed.placements().size() == 128 * 129 - 1,
				"the transparent cell produces no block (was " + holed.placements().size() + ")");

		// --- write the structure and read it back ------------------------------
		Path nbtFile = outDir.resolve("smoketest.nbt");
		StructureNbtWriter.write(result, nbtFile);
		NbtCompound root = NbtIo.readCompressed(nbtFile, NbtSizeTracker.ofUnlimitedBytes());

		NbtList size = root.getListOrEmpty("size");
		check(size.size() == 3, "size is a 3-element int list");
		check(size.getInt(0, -1) == 128 && size.getInt(1, -1) == 129 && size.getInt(2, -1) == 129,
				"size == [128, 129, 129] (was [" + size.getInt(0, -1) + ", " + size.getInt(1, -1)
						+ ", " + size.getInt(2, -1) + "])");

		NbtList paletteList = root.getListOrEmpty("palette");
		check(paletteList.size() == 2, "palette holds only the blocks actually used (was " + paletteList.size() + ")");
		check(root.contains("DataVersion"), "DataVersion present");
		check(root.getInt("DataVersion", -1) == net.minecraft.SharedConstants.getGameVersion().dataVersion().id(),
				"DataVersion matches the running game");
		check(root.contains("entities"), "entities list present");

		NbtList blocks = root.getListOrEmpty("blocks");
		check(blocks.size() == 128 * 129, "one blocks entry per placement (was " + blocks.size() + ")");

		NbtCompound first = blocks.getCompoundOrEmpty(0);
		check(first.getListOrEmpty("pos").size() == 3, "block pos is a 3-element int list");
		check(first.contains("state"), "block state index present");

		boolean indicesOk = true;

		for (int i = 0; i < blocks.size(); i++) {
			int state = blocks.getCompoundOrEmpty(i).getInt("state", -1);

			if (state < 0 || state >= paletteList.size()) {
				indicesOk = false;
				break;
			}
		}

		check(indicesOk, "every state index is inside the palette");

		String firstName = paletteList.getCompoundOrEmpty(0).getString("Name", "");
		check(firstName.startsWith("minecraft:"), "palette entry carries a namespaced Name (was " + firstName + ")");
		check(isGzip(nbtFile), "output is gzip compressed");

		// The strongest compatibility check available offline: hand the file back to
		// vanilla's own structure reader. Litematica reads the same schema.
		net.minecraft.structure.StructureTemplate template = new net.minecraft.structure.StructureTemplate();
		template.readNbt(Registries.BLOCK, root);
		net.minecraft.util.math.Vec3i templateSize = template.getSize();
		check(templateSize.getX() == 128 && templateSize.getY() == 129 && templateSize.getZ() == 129,
				"vanilla StructureTemplate reads it back with size " + templateSize);

		// --- PNG round trip -----------------------------------------------------
		// Colour ids 4 / 12 / 8 are red / blue / white in vanilla; assert against the
		// registry rather than against remembered constants.
		int redId = 4;
		int blueId = 12;
		check(net.minecraft.block.MapColor.get(redId).color == 0xFF0000, "colour id 4 is pure red in this version");
		check(net.minecraft.block.MapColor.get(blueId).color == 0x4040FF, "colour id 12 is water blue in this version");

		byte[] colourful = new byte[128 * 128];

		for (int z = 0; z < 128; z++) {
			for (int x = 0; x < 128; x++) {
				int id = (z < 32) ? redId : (z < 64) ? blueId : (z < 96) ? 8 : 0;
				colourful[x + z * 128] = (byte) (id * 4 + 2);
			}
		}

		Path png = outDir.resolve("smoketest.png");
		MapImageWriter.writePng(colourful, png);
		java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png.toFile());
		check(image.getRGB(0, 0) == 0xFFFF0000,
				String.format("red row is 0xFFFF0000, red and blue not swapped (was 0x%08X)", image.getRGB(0, 0)));
		check(image.getRGB(0, 40) == 0xFF4040FF,
				String.format("blue row is 0xFF4040FF (was 0x%08X)", image.getRGB(0, 40)));
		check((image.getRGB(0, 120) >>> 24) == 0, "transparent row has alpha 0");
		check(MapColors.baseColorId((byte) (13 * 4 + 2)) == 13, "baseColorId unpacks correctly");
		check(MapColors.shade((byte) (13 * 4 + 2)) == 2, "shade unpacks correctly");

		// --- palette.json validation edge cases ---------------------------------
		// file a block under a colour id that is not its own -> concrete rejection
		String wrongId = String.valueOf(paintId == 1 ? 2 : 1);
		String culprit = generated.palette().get(paintId);
		PaletteConfig.LoadResult wrong =
				PaletteConfig.validate("{\"" + wrongId + "\": \"" + culprit + "\"}", null);
		check(!wrong.ok(), "mismatched colour id is rejected");
		check(wrong.errors().size() == 1
						&& wrong.errors().get(0).contains("色ID " + wrongId)
						&& wrong.errors().get(0).contains("実際には色ID " + paintId),
				"mismatch message names both ids: " + wrong.errors());

		check(!PaletteConfig.validate("{\"13\": \"minecraft:not_a_block\"}", null).ok(),
				"unknown block id is rejected");
		check(!PaletteConfig.validate("{\"13\": \"minecraft:white_wool[nope=1]\"}", null).ok(),
				"unknown blockstate property is rejected");
		check(!PaletteConfig.validate("{\"999\": \"minecraft:white_wool\"}", null).ok(),
				"out-of-range colour id is rejected");
		check(!PaletteConfig.validate("{\"0\": \"minecraft:white_wool\"}", null).ok(),
				"colour id 0 is rejected");
		check(!PaletteConfig.validate("{\"abc\": \"minecraft:white_wool\"}", null).ok(),
				"non-numeric key is rejected");
		check(!PaletteConfig.validate("[]", null).ok(), "non-object json is rejected");
		check(PaletteConfig.validate("{\"_comment\": \"hi\"}", null).ok(),
				"underscore keys are treated as comments");
		check(PaletteConfig.validate("{\"" + PaletteConfig.UNUSED_KEY + "\": {\"minecraft:tnt\": 4}}", null).ok(),
				"palette_unused is not validated as a colour mapping");

		PaletteConfig.LoadResult customNoob = PaletteConfig.validate(
				"{\"" + PaletteConfig.NOOB_LINE_KEY + "\": \"minecraft:black_wool\"}", null);
		check(customNoob.ok()
						&& PaletteConfig.mapColorId(customNoob.config().noobLine())
						== PaletteConfig.mapColorId(parse("minecraft:black_wool")),
				"noob_line is configurable");

		// --- filename sanitising -------------------------------------------------
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

	private static BlockState parse(String blockId) throws Exception {
		return BlockArgumentParser.block(Registries.BLOCK, blockId, false).blockState();
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
		if (condition) {
			System.out.println("  ok   " + what);
		} else {
			System.out.println("  FAIL " + what);
			failures++;
		}
	}
}
