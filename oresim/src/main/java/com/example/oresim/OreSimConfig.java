package com.example.oresim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/** Persistent settings, stored as config/oresim.json. */
final class OreSimConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("oresim.json");

	/** Seeds remembered per server address. */
	Map<String, Long> seeds = new LinkedHashMap<>();
	Set<String> ores = new LinkedHashSet<>();
	int range = 5;
	int maxBoxes = 20000;
	boolean enabled = true;
	boolean hideAir = true;
	boolean throughWalls = true;

	transient EnumSet<OreSimulator.Ore> enabledOres = EnumSet.noneOf(OreSimulator.Ore.class);

	static OreSimConfig load() {
		OreSimConfig config = null;

		if (Files.isRegularFile(FILE)) {
			try {
				config = GSON.fromJson(Files.readString(FILE), OreSimConfig.class);
			} catch (Exception e) {
				OreSimClient.LOGGER.warn("Could not read {}, using defaults", FILE, e);
			}
		}

		if (config == null) {
			config = new OreSimConfig();

			for (OreSimulator.Ore ore : OreSimulator.Ore.values()) {
				if (ore.defaultOn) {
					config.ores.add(ore.id);
				}
			}
		}

		if (config.seeds == null) config.seeds = new LinkedHashMap<>();
		if (config.ores == null) config.ores = new LinkedHashSet<>();
		config.range = Math.clamp(config.range, 1, OreSimClient.MAX_RANGE);
		if (config.maxBoxes <= 0) config.maxBoxes = 20000;
		config.maxBoxes = Math.clamp(config.maxBoxes, 100, OreSimClient.MAX_BOXES_LIMIT);
		config.syncOres();
		return config;
	}

	void syncOres() {
		this.enabledOres = EnumSet.noneOf(OreSimulator.Ore.class);

		for (String id : this.ores) {
			OreSimulator.Ore ore = OreSimulator.Ore.byId(id);

			if (ore != null) {
				this.enabledOres.add(ore);
			}
		}
	}

	void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			OreSimClient.LOGGER.warn("Could not write {}", FILE, e);
		}
	}
}
