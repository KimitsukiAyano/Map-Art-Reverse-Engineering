package com.example.oresim;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;

/** Dev-only: load a world, fly underground in spectator, screenshot the ore boxes, quit. */
public final class OreSimClientCheck implements ClientModInitializer {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("oresim-check");
	private int ticks = -1;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void server(Minecraft mc, String command) {
		MinecraftServer server = mc.getSingleplayerServer();
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
	}

	private void tick(Minecraft mc) {
		if (mc.player == null || mc.getSingleplayerServer() == null) {
			return;
		}

		ticks++;

		switch (ticks) {
			case 40 -> {
				server(mc, "gamemode spectator @a");
				server(mc, "time set noon");
				server(mc, "tp @a 8 12 8 30 35");
			}
			case 60 -> {
				mc.player.connection.sendCommand("oresim range " + Integer.getInteger("oresim.check.range", 5));
				mc.player.connection.sendCommand("oresim maxboxes " + Integer.getInteger("oresim.check.maxboxes", 20000));
			}
			case 399 -> LOG.info("CHECK avg tick {} ms", mc.getSingleplayerServer() != null ? String.format("%.2f", mc.getFps() > 0 ? 1000.0 / mc.getFps() : -1) : "?");
			case 400 -> {
				mc.player.connection.sendCommand("oresim");
				Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), c -> LOG.info("CHECK screenshot: {}", c.getString()));
			}
			case 420 -> server(mc, "tp @a 8 -40 8 -60 20");
			case 600 -> Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), c -> LOG.info("CHECK screenshot: {}", c.getString()));
			case 640 -> mc.stop();
			default -> {
			}
		}
	}
}
