package com.example.simpleautofish.config;

import com.example.simpleautofish.SimpleAutoFish;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * All user settings. Stored as {@code config/simpleautofish.json}.
 * Every delay is in milliseconds.
 */
public final class AutoFishConfig {
	// Slider limits (shared with the config screen).
	public static final int REACTION_MAX_MS = 1000; // a bite only lasts ~1-2 s, longer would miss the fish
	public static final int RECAST_MAX_MS = 5000;

	// "Random (auto)" presets: a bell-shaped distribution around a natural human timing.
	private static final double AUTO_REACTION_MEAN = 320, AUTO_REACTION_SD = 90;
	private static final int AUTO_REACTION_MIN = 160, AUTO_REACTION_MAX = 650;
	private static final double AUTO_RECAST_MEAN = 950, AUTO_RECAST_SD = 300;
	private static final int AUTO_RECAST_MIN = 450, AUTO_RECAST_MAX = 2200;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static AutoFishConfig instance;

	// ---- persisted fields ----
	public boolean enabled = true;
	public boolean autoRecast = true;
	public boolean protectRod = true;
	public boolean fishInScreens = false;

	public TimingMode reactionMode = TimingMode.RANDOM_AUTO;
	public int reactionFixedMs = 300;
	public int reactionMinMs = 200;
	public int reactionMaxMs = 500;

	public TimingMode recastMode = TimingMode.RANDOM_AUTO;
	public int recastFixedMs = 1000;
	public int recastMinMs = 600;
	public int recastMaxMs = 1600;

	public static AutoFishConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public long nextReactionMs() {
		return sample(reactionMode, reactionFixedMs, reactionMinMs, reactionMaxMs,
				AUTO_REACTION_MEAN, AUTO_REACTION_SD, AUTO_REACTION_MIN, AUTO_REACTION_MAX);
	}

	public long nextRecastMs() {
		return sample(recastMode, recastFixedMs, recastMinMs, recastMaxMs,
				AUTO_RECAST_MEAN, AUTO_RECAST_SD, AUTO_RECAST_MIN, AUTO_RECAST_MAX);
	}

	private static long sample(TimingMode mode, int fixed, int min, int max,
							   double autoMean, double autoSd, int autoMin, int autoMax) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		return switch (mode) {
			case FIXED -> fixed;
			case RANDOM_RANGE -> min >= max ? min : rnd.nextLong(min, max + 1L);
			case RANDOM_AUTO -> {
				double v = autoMean + rnd.nextGaussian() * autoSd;
				yield Math.round(Math.clamp(v, autoMin, autoMax));
			}
		};
	}

	/** Puts every value back into a sane range (hand-edited files, older versions...). */
	public void sanitize() {
		if (reactionMode == null) reactionMode = TimingMode.RANDOM_AUTO;
		if (recastMode == null) recastMode = TimingMode.RANDOM_AUTO;
		reactionFixedMs = Math.clamp(reactionFixedMs, 0, REACTION_MAX_MS);
		reactionMinMs = Math.clamp(reactionMinMs, 0, REACTION_MAX_MS);
		reactionMaxMs = Math.clamp(reactionMaxMs, reactionMinMs, REACTION_MAX_MS);
		recastFixedMs = Math.clamp(recastFixedMs, 0, RECAST_MAX_MS);
		recastMinMs = Math.clamp(recastMinMs, 0, RECAST_MAX_MS);
		recastMaxMs = Math.clamp(recastMaxMs, recastMinMs, RECAST_MAX_MS);
	}

	public void copyFrom(AutoFishConfig o) {
		enabled = o.enabled;
		autoRecast = o.autoRecast;
		protectRod = o.protectRod;
		fishInScreens = o.fishInScreens;
		reactionMode = o.reactionMode;
		reactionFixedMs = o.reactionFixedMs;
		reactionMinMs = o.reactionMinMs;
		reactionMaxMs = o.reactionMaxMs;
		recastMode = o.recastMode;
		recastFixedMs = o.recastFixedMs;
		recastMinMs = o.recastMinMs;
		recastMaxMs = o.recastMaxMs;
	}

	public AutoFishConfig copy() {
		AutoFishConfig c = new AutoFishConfig();
		c.copyFrom(this);
		return c;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(SimpleAutoFish.MOD_ID + ".json");
	}

	private static AutoFishConfig load() {
		Path p = path();
		AutoFishConfig cfg = null;
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				cfg = GSON.fromJson(r, AutoFishConfig.class);
			} catch (IOException | JsonParseException e) {
				SimpleAutoFish.LOGGER.warn("Could not read {}, using defaults", p, e);
			}
		}
		if (cfg == null) {
			cfg = new AutoFishConfig();
		}
		cfg.sanitize();
		cfg.save();
		return cfg;
	}

	public void save() {
		sanitize();
		Path p = path();
		try {
			Files.createDirectories(p.getParent());
			Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			SimpleAutoFish.LOGGER.error("Could not save {}", p, e);
		}
	}
}
