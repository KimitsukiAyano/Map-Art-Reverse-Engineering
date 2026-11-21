package com.example.oresim;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Dev-only check: generate real chunks, simulate the same chunks, compare block by block. */
public final class OreSimVerify implements ModInitializer {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("oresim-verify");
	private static final int R = Integer.getInteger("oresim.verify.radius", 10);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(OreSimVerify::run);
	}

	private static void run(MinecraftServer server) {
		if (!server.isDedicatedServer()) {
			return;
		}

		try {
			verify(server.overworld(), Level.OVERWORLD);
			verify(server.getLevel(Level.NETHER), Level.NETHER);
		} catch (Throwable t) {
			LOG.error("VERIFY crashed", t);
		}

		server.halt(false);
	}

	private static boolean inVeinRange(OreSimulator.Ore ore, int y) {
		return ore == OreSimulator.Ore.COPPER ? y >= 0 && y <= 50 : ore == OreSimulator.Ore.IRON && y >= -60 && y <= -8;
	}

	private static void verify(ServerLevel level, ResourceKey<Level> dim) {
		long seed = level.getSeed();
		long t0 = System.nanoTime();

		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				level.getChunk(x, z);
			}
		}

		long t1 = System.nanoTime();
		OreSimulator sim = OreSimulator.create(seed, dim);
		long t2 = System.nanoTime();

		Map<Long, OreSimulator.Ore> predicted = new HashMap<>();
		int simR = R - 1;

		for (int x = -simR; x <= simR; x++) {
			for (int z = -simR; z <= simR; z++) {
				for (OreSimulator.Hit h : sim.simulateChunk(level, x, z)) {
					predicted.put(h.pos().asLong(), h.ore());
				}
			}
		}

		long t3 = System.nanoTime();

		// correct, wrong, missed
		Map<OreSimulator.Ore, int[]> stats = new EnumMap<>(OreSimulator.Ore.class);

		for (OreSimulator.Ore o : OreSimulator.Ore.values()) {
			stats.put(o, new int[5]);
		}

		int inner = R - 3;
		int lo = -inner * 16;
		int hi = inner * 16 + 15;

		for (var e : predicted.entrySet()) {
			BlockPos p = BlockPos.of(e.getKey());

			if (p.getX() < lo || p.getX() > hi || p.getZ() < lo || p.getZ() > hi) {
				continue;
			}

			OreSimulator.Ore actual = OreSimulator.Ore.of(level.getBlockState(p).getBlock());
			stats.get(e.getValue())[actual == e.getValue() ? 0 : 1]++;
			if (actual != e.getValue() && inVeinRange(e.getValue(), p.getY())) stats.get(e.getValue())[3]++;
		}

		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

		for (int cx = -inner; cx <= inner; cx++) {
			for (int cz = -inner; cz <= inner; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);

				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						for (int y = level.getMinY(); y < level.getMaxY(); y++) {
							m.set(cx * 16 + x, y, cz * 16 + z);
							OreSimulator.Ore actual = OreSimulator.Ore.of(chunk.getBlockState(m).getBlock());

							if (actual != null && predicted.get(m.asLong()) != actual) {
								stats.get(actual)[2]++;
								if (inVeinRange(actual, m.getY())) stats.get(actual)[4]++;
							}
						}
					}
				}
			}
		}

		LOG.info("VERIFY {} seed={} gen={}ms setup={}ms simulate {} chunks={}ms",
			dim, seed, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (2 * simR + 1) * (2 * simR + 1), (t3 - t2) / 1_000_000);

		for (var e : stats.entrySet()) {
			int[] s = e.getValue();

			if (s[0] + s[1] + s[2] == 0) {
				continue;
			}

			LOG.info(String.format("VERIFY %-14s correct=%6d wrong=%6d missed=%6d  precision=%.4f recall=%.4f (in vein y-range: wrong=%d missed=%d)",
				e.getKey().id, s[0], s[1], s[2], s[0] / (double) Math.max(1, s[0] + s[1]), s[0] / (double) Math.max(1, s[0] + s[2]), s[3], s[4]));
		}
	}
}
