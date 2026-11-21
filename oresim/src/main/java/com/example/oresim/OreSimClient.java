package com.example.oresim;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OreSimClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("oresim");

	/** Upper bound on boxes drawn per tick, nearest first. */
	private static final int MAX_BOXES = 6000;
	/** Main-thread time budget per tick for simulating new chunks. */
	private static final long BUDGET_NANOS = 6_000_000L;

	private static OreSimConfig config;

	private static final OreSimulator.Cache cache = new OreSimulator.Cache();
	private static CompletableFuture<OreSimulator> pending;
	private static OreSimulator simulator;
	private static ResourceKey<Level> simDimension;
	private static long simSeed;
	private static boolean failed;
	private static int tickCounter;

	@Override
	public void onInitializeClient() {
		config = OreSimConfig.load();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(OreSimClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
		// Warm the vanilla worldgen registries up in the background so the first use is instant.
		CompletableFuture.runAsync(() -> {
			try {
				OreSimulator.registries();
			} catch (Throwable t) {
				LOGGER.warn("Pre-building vanilla worldgen registries failed", t);
			}
		});
	}

	// ---- seed --------------------------------------------------------------------------------

	private static String serverKey(Minecraft mc) {
		ServerData server = mc.getCurrentServer();
		return server != null ? server.ip.toLowerCase() : null;
	}

	private static OptionalLong currentSeed(Minecraft mc) {
		IntegratedServer integrated = mc.getSingleplayerServer();

		if (integrated != null) {
			return OptionalLong.of(integrated.overworld().getSeed());
		}

		String key = serverKey(mc);
		Long seed = key == null ? null : config.seeds.get(key);
		return seed == null ? OptionalLong.empty() : OptionalLong.of(seed);
	}

	private static void reset() {
		cache.clear();
		simulator = null;
		simDimension = null;
		pending = null;
		failed = false;
	}

	// ---- tick: simulate + draw ------------------------------------------------------------------

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;

		if (!config.enabled || level == null || mc.player == null) {
			return;
		}

		OptionalLong seed = currentSeed(mc);

		if (seed.isEmpty()) {
			if (tickCounter++ % 1200 == 0) {
				message(mc, Component.literal("シードが未設定です: /oresim seed <シード値>").withStyle(ChatFormatting.YELLOW));
			}

			return;
		}

		ResourceKey<Level> dimension = level.dimension();

		if (simulator == null || !dimension.equals(simDimension) || simSeed != seed.getAsLong()) {
			if (!prepare(dimension, seed.getAsLong())) {
				return;
			}
		}

		BlockPos playerPos = mc.player.blockPosition();
		int pcx = playerPos.getX() >> 4;
		int pcz = playerPos.getZ() >> 4;
		int range = config.range;

		simulateAround(level, pcx, pcz, range);
		evictFar(pcx, pcz, range + 3);
		draw(level, playerPos, pcx, pcz, range);
	}

	/** Returns true once a simulator for (dimension, seed) is ready. */
	private static boolean prepare(ResourceKey<Level> dimension, long seed) {
		if (pending == null || !dimension.equals(simDimension) || simSeed != seed) {
			cache.clear();
			simulator = null;
			failed = false;
			simDimension = dimension;
			simSeed = seed;
			pending = CompletableFuture.supplyAsync(() -> OreSimulator.create(seed, dimension));
			return false;
		}

		if (!pending.isDone() || failed) {
			return false;
		}

		try {
			simulator = pending.join();
		} catch (Throwable t) {
			failed = true;
			LOGGER.error("OreSim could not set up world generation for {}", dimension, t);
			message(Minecraft.getInstance(), Component.literal("OreSim の初期化に失敗しました (ログを確認してください)").withStyle(ChatFormatting.RED));
			return false;
		}

		return simulator != null;
	}

	private static void simulateAround(ClientLevel level, int pcx, int pcz, int range) {
		long deadline = System.nanoTime() + BUDGET_NANOS;

		// Spiral-ish: nearest rings first.
		for (int ring = 0; ring <= range; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}

					int cx = pcx + dx;
					int cz = pcz + dz;
					long key = OreSimulator.chunkKey(cx, cz);

					if (cache.containsKey(key) || !level.hasChunk(cx, cz)) {
						continue;
					}

					try {
						cache.put(key, simulator.simulateChunk(level, cx, cz));
					} catch (Throwable t) {
						LOGGER.error("OreSim failed on chunk {}, {}", cx, cz, t);
						cache.put(key, List.of());
					}

					if (System.nanoTime() > deadline) {
						return;
					}
				}
			}
		}
	}

	private static void evictFar(int pcx, int pcz, int keep) {
		cache.long2ObjectEntrySet().removeIf(e -> {
			long key = e.getLongKey();
			int cx = (int) key;
			int cz = (int) (key >> 32);
			return Math.abs(cx - pcx) > keep || Math.abs(cz - pcz) > keep;
		});
	}

	private static void draw(ClientLevel level, BlockPos playerPos, int pcx, int pcz, int range) {
		List<OreSimulator.Hit> visible = new ArrayList<>();
		long maxDist = (long) (range * 16 + 16) * (range * 16 + 16);

		for (int dx = -range; dx <= range; dx++) {
			for (int dz = -range; dz <= range; dz++) {
				List<OreSimulator.Hit> hits = cache.get(OreSimulator.chunkKey(pcx + dx, pcz + dz));

				if (hits == null) {
					continue;
				}

				for (OreSimulator.Hit hit : hits) {
					if (!config.enabledOres.contains(hit.ore()) || hit.pos().distSqr(playerPos) > maxDist) {
						continue;
					}

					if (config.hideAir && level.isLoaded(hit.pos()) && level.getBlockState(hit.pos()).isAir()) {
						continue; // already mined out (or carved by something we did not simulate)
					}

					visible.add(hit);
				}
			}
		}

		if (visible.size() > MAX_BOXES) {
			visible.sort(Comparator.comparingDouble(h -> h.pos().distSqr(playerPos)));
			visible = visible.subList(0, MAX_BOXES);
		}

		for (OreSimulator.Hit hit : visible) {
			GizmoProperties box = Gizmos.cuboid(hit.pos(), 0.002F, GizmoStyle.stroke(hit.ore().color, 2.0F));

			if (config.throughWalls) {
				box.setAlwaysOnTop();
			}
		}
	}

	// ---- commands ------------------------------------------------------------------------------

	private static void message(Minecraft mc, Component text) {
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[OreSim] ").withStyle(ChatFormatting.AQUA).append(text));
	}

	private static int feedback(CommandContext<FabricClientCommandSource> ctx, String text) {
		ctx.getSource().sendFeedback(Component.literal("[OreSim] ").withStyle(ChatFormatting.AQUA).append(Component.literal(text)));
		return 1;
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("oresim")
			.executes(OreSimClient::status)
			.then(literal("seed")
				.then(argument("seed", StringArgumentType.greedyString()).executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					String raw = StringArgumentType.getString(ctx, "seed").trim();
					OptionalLong parsed = WorldOptions.parseSeed(raw);

					if (parsed.isEmpty()) {
						ctx.getSource().sendError(Component.literal("シード値が空です"));
						return 0;
					}

					String key = serverKey(mc);

					if (key == null) {
						return feedback(ctx, "シングルプレイではワールドのシードを自動で使うため設定不要です");
					}

					config.seeds.put(key, parsed.getAsLong());
					config.save();
					reset();
					return feedback(ctx, key + " のシードを " + parsed.getAsLong() + " に設定しました");
				})))
			.then(literal("on").executes(ctx -> setEnabled(ctx, true)))
			.then(literal("off").executes(ctx -> setEnabled(ctx, false)))
			.then(literal("toggle").executes(ctx -> setEnabled(ctx, !config.enabled)))
			.then(literal("range")
				.then(argument("chunks", IntegerArgumentType.integer(1, 32)).executes(ctx -> {
					config.range = IntegerArgumentType.getInteger(ctx, "chunks");
					config.save();
					return feedback(ctx, "範囲を " + config.range + " チャンクにしました");
				})))
			.then(literal("ore")
				.then(argument("name", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
						java.util.Arrays.stream(OreSimulator.Ore.values()).map(o -> o.id), builder))
					.executes(ctx -> {
						String id = StringArgumentType.getString(ctx, "name");
						OreSimulator.Ore ore = OreSimulator.Ore.byId(id);

						if (ore == null) {
							ctx.getSource().sendError(Component.literal("不明な鉱石: " + id));
							return 0;
						}

						boolean on = !config.ores.remove(id);

						if (on) {
							config.ores.add(id);
						}

						config.syncOres();
						config.save();
						return feedback(ctx, id + ": " + (on ? "表示" : "非表示"));
					})))
			.then(literal("hideair").executes(ctx -> {
				config.hideAir = !config.hideAir;
				config.save();
				return feedback(ctx, "掘られた(空気になった)位置を隠す: " + (config.hideAir ? "ON" : "OFF"));
			}))
			.then(literal("xray").executes(ctx -> {
				config.throughWalls = !config.throughWalls;
				config.save();
				return feedback(ctx, "壁越し表示: " + (config.throughWalls ? "ON" : "OFF"));
			}))
			.then(literal("reload").executes(ctx -> {
				reset();
				return feedback(ctx, "キャッシュを消去して再計算します");
			})));
	}

	private static int setEnabled(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
		config.enabled = enabled;
		config.save();
		return feedback(ctx, enabled ? "有効" : "無効");
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft mc = ctx.getSource().getClient();
		OptionalLong seed = currentSeed(mc);
		StringBuilder ores = new StringBuilder();

		for (OreSimulator.Ore ore : OreSimulator.Ore.values()) {
			ores.append(config.enabledOres.contains(ore) ? "§a" : "§7").append(ore.id).append("§r ");
		}

		feedback(ctx, (config.enabled ? "有効" : "無効") + " / 範囲 " + config.range + " チャンク / シード "
			+ (seed.isPresent() ? seed.getAsLong() : "未設定") + (simulator == null && seed.isPresent() && config.enabled ? " (準備中)" : ""));
		feedback(ctx, "鉱石: " + ores);
		int total = 0;

		for (List<OreSimulator.Hit> hits : cache.values()) {
			total += hits.size();
		}

		return feedback(ctx, "計算済み " + cache.size() + " チャンク / 鉱石ブロック " + total + " 個");
	}
}
