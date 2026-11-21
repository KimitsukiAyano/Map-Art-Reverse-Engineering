package com.example.oresim;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * Re-runs the vanilla ore features of one dimension for a given world seed.
 *
 * The feature list, its per-step ordering and every placement modifier come from
 * {@link VanillaRegistries}, i.e. the very same bootstrap code the vanilla data pack
 * is generated from, so counts, height ranges and feature indices match 26.3 exactly.
 * Only the two ore algorithms are re-implemented here, because the real ones write
 * straight into chunk sections; the code below is a line-by-line copy of them that
 * writes into an overlay instead.
 */
final class OreSimulator {
	private static volatile HolderLookup.Provider vanilla;

	private final long seed;
	private final NoiseBasedChunkGenerator generator;
	private final List<FeatureSorter.StepFeatureData> steps;
	private final Set<Holder<Biome>> possibleBiomes;
	private final HolderLookup.RegistryLookup<Biome> biomeLookup;
	private final BlockState fallbackUpper;
	private final BlockState fallbackLower;

	record Hit(BlockPos pos, Ore ore) {
	}

	private OreSimulator(long seed, HolderLookup.Provider registries, BiomeSource biomeSource, ResourceKey<NoiseGeneratorSettings> settings,
						 BlockState fallbackUpper, BlockState fallbackLower) {
		this.seed = seed;
		this.biomeLookup = registries.lookupOrThrow(Registries.BIOME);
		this.generator = new NoiseBasedChunkGenerator(biomeSource, registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settings));
		this.possibleBiomes = biomeSource.possibleBiomes();
		// Same call ChunkGenerator makes for its private featuresPerStep supplier.
		this.steps = FeatureSorter.buildFeaturesPerStep(List.copyOf(biomeSource.possibleBiomes()), b -> b.value().getGenerationSettings().features(), true);
		this.fallbackUpper = fallbackUpper;
		this.fallbackLower = fallbackLower;
	}

	/** Builds the simulator for a dimension, or returns null for a dimension without ores (the End, custom ones). */
	static OreSimulator create(long seed, ResourceKey<Level> dimension) {
		HolderLookup.Provider registries = registries();
		var presets = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

		if (dimension.equals(Level.OVERWORLD)) {
			return new OreSimulator(seed, registries, MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
				NoiseGeneratorSettings.OVERWORLD, Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState());
		}

		if (dimension.equals(Level.NETHER)) {
			return new OreSimulator(seed, registries, MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)),
				NoiseGeneratorSettings.NETHER, Blocks.NETHERRACK.defaultBlockState(), Blocks.NETHERRACK.defaultBlockState());
		}

		return null;
	}

	static HolderLookup.Provider registries() {
		HolderLookup.Provider result = vanilla;

		if (result == null) {
			synchronized (OreSimulator.class) {
				result = vanilla;

				if (result == null) {
					result = VanillaRegistries.createWorldLookup();
					vanilla = result;
				}
			}
		}

		return result;
	}

	/**
	 * Runs every ore feature that vanilla would run while decorating chunk (cx, cz).
	 * The returned hits may lie in neighbouring chunks, exactly like real ore blobs.
	 */
	List<Hit> simulateChunk(Level level, int cx, int cz) {
		int minY = level.getMinY();
		int height = level.getHeight();
		Map<Long, BlockState> overlay = new HashMap<>();
		Map<Holder<Biome>, Holder<Biome>> biomeCache = new IdentityHashMap<>();
		Map<Long, Integer> surfaceCache = new HashMap<>();

		WorldGenLevel sim = SimLevel.create(
			this.seed, minY, height, level.getSeaLevel(),
			pos -> this.biomeAt(level, pos, biomeCache),
			pos -> this.blockAt(level, pos, overlay, minY, height),
			(x, z) -> surfaceCache.computeIfAbsent(ChunkPos.pack(x, z), k -> this.surfaceAt(level, x, z, minY, minY + height))
		);

		SectionPos sectionPos = SectionPos.of(new ChunkPos(cx, cz), level.getMinSectionY());
		BlockPos origin = sectionPos.origin();

		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		long decorationSeed = random.setDecorationSeed(this.seed, origin.getX(), origin.getZ());

		Set<Holder<Biome>> chunkBiomes = new ObjectArraySet<>();

		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!level.hasChunk(cx + dx, cz + dz)) {
					continue;
				}

				LevelChunk chunk = level.getChunk(cx + dx, cz + dz);

				for (LevelChunkSection section : chunk.getSections()) {
					section.getBiomes().getAll(b -> chunkBiomes.add(this.mapBiome(b, biomeCache)));
				}
			}
		}

		chunkBiomes.retainAll(this.possibleBiomes);
		List<Hit> hits = new ArrayList<>();

		for (int step = 0; step < this.steps.size(); step++) {
			FeatureSorter.StepFeatureData stepData = this.steps.get(step);
			IntSet indices = new IntArraySet();

			for (Holder<Biome> biome : chunkBiomes) {
				List<HolderSet<PlacedFeature>> features = biome.value().getGenerationSettings().features();

				if (step < features.size()) {
					features.get(step).stream().map(Holder::value).forEach(f -> indices.add(stepData.indexMapping().applyAsInt(f)));
				}
			}

			int[] sorted = indices.toIntArray();
			Arrays.sort(sorted);

			for (int index : sorted) {
				PlacedFeature placed = stepData.features().get(index);
				Feature feature = placed.feature().value();

				if (!(feature instanceof AbstractOreFeature ore)) {
					continue;
				}

				random.setFeatureSeed(decorationSeed, index, step);
				this.place(sim, placed, ore, random, origin, overlay, hits);
			}
		}

		return hits;
	}

	/** Mirror of FeaturePlacer#placeWithBiomeCheck, but ending in our own ore placement. */
	private void place(WorldGenLevel sim, PlacedFeature placed, AbstractOreFeature ore, RandomSource random, BlockPos origin,
					   Map<Long, BlockState> overlay, List<Hit> hits) {
		PlacementContext context = new PlacementContext(sim, this.generator, Optional.of(placed));
		List<PlacementModifier> placement = placed.placement();

		if (placement.isEmpty()) {
			this.placeOre(sim, ore, random, origin, overlay, hits);
			return;
		}

		List<BlockPos> positions = new ArrayList<>();
		List<Integer> modifierIndices = new ArrayList<>();
		List<BlockPos> modified = new ArrayList<>();
		positions.add(origin);
		modifierIndices.add(0);

		while (!positions.isEmpty()) {
			BlockPos pos = positions.removeLast();
			int modifierIndex = modifierIndices.removeLast();
			modified.clear();
			placement.get(modifierIndex).modify(context, random, pos, modified::add);
			int next = modifierIndex + 1;

			if (next < placement.size()) {
				for (int i = modified.size() - 1; i >= 0; i--) {
					positions.add(modified.get(i));
					modifierIndices.add(next);
				}
			} else {
				for (BlockPos target : List.copyOf(modified)) {
					this.placeOre(sim, ore, random, target, overlay, hits);
				}
			}
		}
	}

	private void placeOre(WorldGenLevel sim, AbstractOreFeature ore, RandomSource random, BlockPos origin, Map<Long, BlockState> overlay, List<Hit> hits) {
		if (ore instanceof ScatteredOreFeature) {
			this.placeScattered(sim, ore, random, origin, overlay, hits);
		} else if (ore instanceof OreFeature) {
			this.placeBlob(sim, ore, random, origin, overlay, hits);
		}
	}

	private void set(BlockPos pos, BlockState state, Map<Long, BlockState> overlay, List<Hit> hits) {
		overlay.put(pos.asLong(), state);
		Ore type = Ore.of(state.getBlock());

		if (type != null) {
			hits.add(new Hit(pos.immutable(), type));
		}
	}

	// ---- copy of ScatteredOreFeature#place -------------------------------------------------

	private void placeScattered(WorldGenLevel level, AbstractOreFeature ore, RandomSource random, BlockPos origin, Map<Long, BlockState> overlay, List<Hit> hits) {
		int numberOfTries = random.nextInt(ore.size() + 1);
		BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();

		for (int i = 0; i < numberOfTries; i++) {
			int max = Math.min(i, 7);
			int xd = Math.round((random.nextFloat() - random.nextFloat()) * max);
			int yd = Math.round((random.nextFloat() - random.nextFloat()) * max);
			int zd = Math.round((random.nextFloat() - random.nextFloat()) * max);
			targetPos.setWithOffset(origin, xd, yd, zd);
			BlockState blockState = level.getBlockState(targetPos);

			for (BlockReplacement targetState : ore.targetStates()) {
				if (ore.canPlaceOre(blockState, level::getBlockState, random, targetState, targetPos)) {
					this.set(targetPos, targetState.state(), overlay, hits);
					break;
				}
			}
		}
	}

	// ---- copy of OreFeature#place / #doPlace --------------------------------------------------

	private void placeBlob(WorldGenLevel level, AbstractOreFeature ore, RandomSource random, BlockPos origin, Map<Long, BlockState> overlay, List<Hit> hits) {
		int size = ore.size();
		float dir = random.nextFloat() * (float) Math.PI;
		float spreadXY = size / 8.0F;
		int maxRadius = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
		double x0 = origin.getX() + Math.sin(dir) * spreadXY;
		double x1 = origin.getX() - Math.sin(dir) * spreadXY;
		double z0 = origin.getZ() + Math.cos(dir) * spreadXY;
		double z1 = origin.getZ() - Math.cos(dir) * spreadXY;
		double y0 = origin.getY() + random.nextInt(3) - 2;
		double y1 = origin.getY() + random.nextInt(3) - 2;
		int xStart = origin.getX() - Mth.ceil(spreadXY) - maxRadius;
		int yStart = origin.getY() - 2 - maxRadius;
		int zStart = origin.getZ() - Mth.ceil(spreadXY) - maxRadius;
		int sizeXZ = 2 * (Mth.ceil(spreadXY) + maxRadius);
		int sizeY = 2 * (2 + maxRadius);

		for (int xprobe = xStart; xprobe <= xStart + sizeXZ; xprobe++) {
			for (int zprobe = zStart; zprobe <= zStart + sizeXZ; zprobe++) {
				if (yStart <= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, xprobe, zprobe)) {
					this.doPlaceBlob(level, ore, random, x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY, overlay, hits);
					return;
				}
			}
		}
	}

	private void doPlaceBlob(WorldGenLevel level, AbstractOreFeature ore, RandomSource random, double x0, double x1, double z0, double z1, double y0, double y1,
							 int xStart, int yStart, int zStart, int sizeXZ, int sizeY, Map<Long, BlockState> overlay, List<Hit> hits) {
		int size = ore.size();
		BitSet tested = new BitSet(sizeXZ * sizeY * sizeXZ);
		BlockPos.MutableBlockPos orePos = new BlockPos.MutableBlockPos();
		double[] data = new double[size * 4];

		for (int i = 0; i < size; i++) {
			float step = (float) i / size;
			double xx = Mth.lerp(step, x0, x1);
			double yy = Mth.lerp(step, y0, y1);
			double zz = Mth.lerp(step, z0, z1);
			double ss = random.nextDouble() * size / 16.0;
			double r = ((Mth.sin((float) Math.PI * step) + 1.0F) * ss + 1.0) / 2.0;
			data[i * 4] = xx;
			data[i * 4 + 1] = yy;
			data[i * 4 + 2] = zz;
			data[i * 4 + 3] = r;
		}

		for (int i1 = 0; i1 < size - 1; i1++) {
			if (!(data[i1 * 4 + 3] <= 0.0)) {
				for (int i2 = i1 + 1; i2 < size; i2++) {
					if (!(data[i2 * 4 + 3] <= 0.0)) {
						double dx = data[i1 * 4] - data[i2 * 4];
						double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
						double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
						double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];

						if (dr * dr > dx * dx + dy * dy + dz * dz) {
							if (dr > 0.0) {
								data[i2 * 4 + 3] = -1.0;
							} else {
								data[i1 * 4 + 3] = -1.0;
							}
						}
					}
				}
			}
		}

		for (int i = 0; i < size; i++) {
			double r = data[i * 4 + 3];

			if (r < 0.0) {
				continue;
			}

			double xx = data[i * 4];
			double yy = data[i * 4 + 1];
			double zz = data[i * 4 + 2];
			int xMin = Math.max(Mth.floor(xx - r), xStart);
			int yMin = Math.max(Mth.floor(yy - r), yStart);
			int zMin = Math.max(Mth.floor(zz - r), zStart);
			int xMax = Math.max(Mth.floor(xx + r), xMin);
			int yMax = Math.max(Mth.floor(yy + r), yMin);
			int zMax = Math.max(Mth.floor(zz + r), zMin);

			for (int x = xMin; x <= xMax; x++) {
				double xd = (x + 0.5 - xx) / r;

				if (xd * xd < 1.0) {
					for (int y = yMin; y <= yMax; y++) {
						double yd = (y + 0.5 - yy) / r;

						if (xd * xd + yd * yd < 1.0) {
							for (int z = zMin; z <= zMax; z++) {
								double zd = (z + 0.5 - zz) / r;

								if (xd * xd + yd * yd + zd * zd < 1.0 && !level.isOutsideBuildHeight(y)) {
									int bitSetIndex = x - xStart + (y - yStart) * sizeXZ + (z - zStart) * sizeXZ * sizeY;

									if (!tested.get(bitSetIndex)) {
										tested.set(bitSetIndex);
										orePos.set(x, y, z);
										BlockState blockState = level.getBlockState(orePos);

										for (BlockReplacement targetState : ore.targetStates()) {
											if (ore.canPlaceOre(blockState, level::getBlockState, random, targetState, orePos)) {
												this.set(orePos, targetState.state(), overlay, hits);
												break;
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	// ---- world access -----------------------------------------------------------------------

	private Holder<Biome> mapBiome(Holder<Biome> clientBiome, Map<Holder<Biome>, Holder<Biome>> cache) {
		return cache.computeIfAbsent(clientBiome, b -> b.unwrapKey()
			.flatMap(key -> this.biomeLookup.get(key).<Holder<Biome>>map(h -> h))
			.orElse(b));
	}

	private Holder<Biome> biomeAt(Level level, BlockPos pos, Map<Holder<Biome>, Holder<Biome>> cache) {
		return this.mapBiome(level.getBiome(pos), cache);
	}

	/**
	 * The block a feature would have seen. Loaded chunks are read from the client world with
	 * ore blocks turned back into their host rock (they did not exist yet when the ore ran,
	 * and anti-xray servers send fake ones); unloaded ones are assumed to be solid host rock.
	 */
	private BlockState blockAt(Level level, BlockPos pos, Map<Long, BlockState> overlay, int minY, int height) {
		BlockState placed = overlay.get(pos.asLong());

		if (placed != null) {
			return placed;
		}

		int y = pos.getY();

		if (y < minY || y >= minY + height) {
			return Blocks.AIR.defaultBlockState();
		}

		if (level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) {
			BlockState state = level.getBlockState(pos);
			BlockState host = Ore.hostRock(state.getBlock());
			return host != null ? host : state;
		}

		return y < 0 ? this.fallbackLower : this.fallbackUpper;
	}

	/**
	 * Estimates OCEAN_FLOOR_WG, the heightmap ore features check: the top block that blocks
	 * motion as it was right after terrain generation. Trees, ice and other blocks placed by
	 * later decoration steps are skipped, as are fluids (not motion-blocking).
	 */
	private int surfaceAt(Level level, int x, int z, int minY, int top) {
		if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
			return top;
		}

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);

		for (; pos.getY() >= minY; pos.move(0, -1, 0)) {
			BlockState state = level.getBlockState(pos);

			if (state.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP) && !isLaterDecoration(state)) {
				return pos.getY() + 1;
			}
		}

		return minY;
	}

	private static boolean isLaterDecoration(BlockState state) {
		return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(Blocks.ICE) || state.is(Blocks.CACTUS)
			|| state.is(Blocks.BAMBOO) || state.is(Blocks.PUMPKIN) || state.is(Blocks.MELON) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
			|| state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.BEE_NEST) || state.is(Blocks.SNOW_BLOCK)
			|| state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD);
	}

	/** Ore kinds that can be shown, keyed by the blocks vanilla features place. */
	enum Ore {
		DIAMOND("diamond", 0xFF4AEDD9, true),
		ANCIENT_DEBRIS("ancient_debris", 0xFF9A5B3C, true),
		EMERALD("emerald", 0xFF17DD62, true),
		GOLD("gold", 0xFFFFD700, true),
		IRON("iron", 0xFFE3B99A, true),
		REDSTONE("redstone", 0xFFFF2020, true),
		LAPIS("lapis", 0xFF2557E0, true),
		COPPER("copper", 0xFFE0773C, false),
		COAL("coal", 0xFF555555, false),
		NETHER_GOLD("nether_gold", 0xFFFFB020, false),
		QUARTZ("quartz", 0xFFF0EAE0, false);

		final String id;
		final int color;
		final boolean defaultOn;

		private static final Map<Block, Ore> BY_BLOCK = new IdentityHashMap<>();
		private static final Map<Block, BlockState> HOST = new IdentityHashMap<>();

		Ore(String id, int color, boolean defaultOn) {
			this.id = id;
			this.color = color;
			this.defaultOn = defaultOn;
		}

		static Ore byId(String id) {
			for (Ore ore : values()) {
				if (ore.id.equals(id)) {
					return ore;
				}
			}

			return null;
		}

		static Ore of(Block block) {
			init();
			return BY_BLOCK.get(block);
		}

		static BlockState hostRock(Block block) {
			init();
			return HOST.get(block);
		}

		private static synchronized void init() {
			if (!BY_BLOCK.isEmpty()) {
				return;
			}

			put(Blocks.DIAMOND_ORE, DIAMOND, Blocks.STONE);
			put(Blocks.DEEPSLATE_DIAMOND_ORE, DIAMOND, Blocks.DEEPSLATE);
			put(Blocks.EMERALD_ORE, EMERALD, Blocks.STONE);
			put(Blocks.DEEPSLATE_EMERALD_ORE, EMERALD, Blocks.DEEPSLATE);
			put(Blocks.GOLD_ORE, GOLD, Blocks.STONE);
			put(Blocks.DEEPSLATE_GOLD_ORE, GOLD, Blocks.DEEPSLATE);
			put(Blocks.IRON_ORE, IRON, Blocks.STONE);
			put(Blocks.DEEPSLATE_IRON_ORE, IRON, Blocks.DEEPSLATE);
			put(Blocks.REDSTONE_ORE, REDSTONE, Blocks.STONE);
			put(Blocks.DEEPSLATE_REDSTONE_ORE, REDSTONE, Blocks.DEEPSLATE);
			put(Blocks.LAPIS_ORE, LAPIS, Blocks.STONE);
			put(Blocks.DEEPSLATE_LAPIS_ORE, LAPIS, Blocks.DEEPSLATE);
			put(Blocks.COPPER_ORE, COPPER, Blocks.STONE);
			put(Blocks.DEEPSLATE_COPPER_ORE, COPPER, Blocks.DEEPSLATE);
			put(Blocks.COAL_ORE, COAL, Blocks.STONE);
			put(Blocks.DEEPSLATE_COAL_ORE, COAL, Blocks.DEEPSLATE);
			put(Blocks.NETHER_GOLD_ORE, NETHER_GOLD, Blocks.NETHERRACK);
			put(Blocks.NETHER_QUARTZ_ORE, QUARTZ, Blocks.NETHERRACK);
			put(Blocks.ANCIENT_DEBRIS, ANCIENT_DEBRIS, Blocks.NETHERRACK);

			// Any other *_ore block (future additions) still reverts to plausible host rock.
			for (Block block : BuiltInRegistries.BLOCK) {
				String path = BuiltInRegistries.BLOCK.getKey(block).getPath();

				if (path.endsWith("_ore") && !HOST.containsKey(block)) {
					HOST.put(block, (path.startsWith("deepslate_") ? Blocks.DEEPSLATE : path.startsWith("nether_") ? Blocks.NETHERRACK : Blocks.STONE).defaultBlockState());
				}
			}
		}

		private static void put(Block block, Ore ore, Block host) {
			BY_BLOCK.put(block, ore);
			HOST.put(block, host.defaultBlockState());
		}
	}

	static long chunkKey(int cx, int cz) {
		return ChunkPos.pack(cx, cz);
	}

	static final class Cache extends Long2ObjectOpenHashMap<List<Hit>> {
	}
}
