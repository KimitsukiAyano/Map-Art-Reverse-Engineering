package com.example.simpleautofish.test;

import com.example.simpleautofish.config.AutoFishConfig;
import com.example.simpleautofish.config.AutoFishConfigScreen;
import com.example.simpleautofish.config.TimingMode;
import com.example.simpleautofish.mixin.FishingHookAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class AutoFishClientGameTest implements FabricClientGameTest {
	private static final int CATCH_TIMEOUT = 20 * 120;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		AutoFishConfig cfg = AutoFishConfig.get();

		// ---- config screen in every mode ----
		cfg.reactionMode = TimingMode.RANDOM_AUTO;
		cfg.recastMode = TimingMode.RANDOM_AUTO;
		ctx.setScreen(() -> new AutoFishConfigScreen(null));
		ctx.takeScreenshot("config-auto");
		cfg.reactionMode = TimingMode.FIXED;
		cfg.recastMode = TimingMode.RANDOM_RANGE;
		ctx.setScreen(() -> new AutoFishConfigScreen(null));
		ctx.takeScreenshot("config-fixed-range");
		ctx.setScreen(() -> null);

		cfg.enabled = true;
		cfg.autoRecast = true;
		cfg.protectRod = true;
		cfg.reactionFixedMs = 250;
		cfg.recastMinMs = 500;
		cfg.recastMaxMs = 1200;
		cfg.save();

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			var server = sp.getServer();
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamemode survival @a");
			server.runCommand("fill -8 100 -8 8 103 8 water");
			server.runCommand("setblock 0 103 0 stone");
			server.runCommand("tp @a 0.5 104 0.5 0 35");
			server.runCommand("item replace entity @a hotbar.0 with fishing_rod[enchantments={lure:3}]");
			server.runCommand("effect give @a resistance infinite 255 true");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(10);

			// The player casts once by hand; everything after that is the mod.
			// Record the packets of this real right click to compare the mod's clicks against.
			PacketLog.start();
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitFor(mc -> mc.player.fishing != null, 100);
			ctx.waitTicks(2);
			List<String> vanilla = PacketLog.stopAndGetUseTick();
			System.out.println("[autofish-test] cast by hand OK, packets of that tick: " + vanilla);

			// Each catch: wait for the bite, then measure how long the mod takes to reel in.
			Object[][] rounds = {
					{TimingMode.FIXED, 250},
					{TimingMode.FIXED, 800},
					{TimingMode.RANDOM_AUTO, 0},
					{TimingMode.RANDOM_RANGE, 0},
			};
			cfg.reactionMinMs = 400;
			cfg.reactionMaxMs = 600;
			for (int i = 0; i < rounds.length; i++) {
				cfg.reactionMode = (TimingMode) rounds[i][0];
				cfg.reactionFixedMs = (Integer) rounds[i][1];
				final int target = i + 1;
				ctx.waitFor(mc -> mc.player.fishing != null
						&& ((FishingHookAccessor) mc.player.fishing).simpleautofish$isBiting(), CATCH_TIMEOUT);
				PacketLog.start();
				int reelTicks = ctx.waitFor(mc -> mc.player.fishing == null, 60);
				ctx.waitTicks(1);
				assertSamePackets("reel #" + target, vanilla, PacketLog.stopAndGetUseTick());
				ctx.waitFor(mc -> fishCount(mc.player) >= target, 60);
				System.out.println("[autofish-test] catch #" + target + " mode=" + rounds[i][0] + " fixed=" + rounds[i][1]
						+ "ms -> reeled " + reelTicks + " ticks (~" + reelTicks * 50 + " ms) after the bite; inv: " + describe(ctx));
				PacketLog.start();
				int recastTicks = ctx.waitFor(mc -> mc.player.fishing != null, 20 * 6); // auto recast
				ctx.waitTicks(2);
				assertSamePackets("recast #" + target, vanilla, PacketLog.stopAndGetUseTick());
				System.out.println("[autofish-test] recast #" + target + " after " + recastTicks + " ticks");
			}
			ctx.takeScreenshot("fishing");

			// ---- open screen: OFF waits, ON keeps fishing ----
			cfg.reactionMode = TimingMode.FIXED;
			cfg.reactionFixedMs = 200;
			cfg.fishInScreens = false;
			ctx.runOnClient(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
			ctx.waitFor(mc -> mc.player.fishing != null
					&& ((FishingHookAccessor) mc.player.fishing).simpleautofish$isBiting(), CATCH_TIMEOUT);
			int held = ctx.computeOnClient(mc -> fishCount(mc.player));
			ctx.waitFor(mc -> mc.player.fishing == null
					|| !((FishingHookAccessor) mc.player.fishing).simpleautofish$isBiting(), 100);
			ctx.waitTicks(10);
			boolean stillOut = ctx.computeOnClient(mc -> mc.player.fishing != null);
			int after = ctx.computeOnClient(mc -> fishCount(mc.player));
			System.out.println("[autofish-test] screen open, setting OFF: rod still out=" + stillOut + ", fish " + held + " -> " + after);
			if (!stillOut || after != held) throw new AssertionError("reeled in although a screen was open and fishInScreens=false");

			cfg.fishInScreens = true;
			ctx.waitFor(mc -> fishCount(mc.player) > after, CATCH_TIMEOUT);
			ctx.waitFor(mc -> mc.player.fishing != null, 20 * 6);
			boolean screenStillOpen = ctx.computeOnClient(mc -> mc.gui.screen() instanceof InventoryScreen);
			System.out.println("[autofish-test] screen open, setting ON: caught and recast, inventory still open=" + screenStillOpen);
			if (!screenStillOpen) throw new AssertionError("screen was closed");
			ctx.takeScreenshot("fishing-in-inventory");
			ctx.setScreen(() -> null);
			cfg.fishInScreens = false;

			// ---- rod protection: a nearly broken rod must not be recast ----
			// (swapping one rod for another keeps the bobber out, so empty the hand first)
			server.runCommand("item replace entity @a hotbar.0 with air");
			ctx.waitFor(mc -> mc.player.fishing == null, 100);
			server.runCommand("item replace entity @a hotbar.0 with fishing_rod[enchantments={lure:3},damage=62]");
			ctx.waitFor(mc -> mc.player.getMainHandItem().is(Items.FISHING_ROD), 100);
			ctx.waitTicks(5);
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitFor(mc -> mc.player.fishing != null, 100);
			int before = ctx.computeOnClient(mc -> fishCount(mc.player));
			ctx.waitFor(mc -> fishCount(mc.player) > before, CATCH_TIMEOUT);
			ctx.waitTicks(20 * 4);
			boolean stillEnabled = cfg.enabled;
			boolean recast = ctx.computeOnClient(mc -> mc.player.fishing != null);
			ItemStack rod = ctx.computeOnClient(mc -> mc.player.getMainHandItem().copy());
			System.out.println("[autofish-test] protection: enabled=" + stillEnabled + " recast=" + recast
					+ " rod=" + rod + " durabilityLeft=" + (rod.getMaxDamage() - rod.getDamageValue()));
			if (stillEnabled || recast || rod.isEmpty()) {
				throw new AssertionError("rod protection did not stop the mod");
			}
			System.out.println("[autofish-test] ALL OK");
		}
	}

	/** The mod's click must produce the same packets, in the same order, in one client tick, as a real one. */
	private static void assertSamePackets(String what, List<String> vanilla, List<String> mod) {
		System.out.println("[autofish-test]   " + what + " packets of that tick: " + mod);
		List<String> a = new ArrayList<>(vanilla), b = new ArrayList<>(mod);
		// Movement packets depend on whether the player moved/turned that tick, not on the click.
		a.removeIf(n -> n.startsWith("ServerboundMovePlayerPacket"));
		b.removeIf(n -> n.startsWith("ServerboundMovePlayerPacket"));
		if (!a.equals(b)) throw new AssertionError(what + ": packets differ from a vanilla right click: " + mod + " vs " + vanilla);
	}

	private static int fishCount(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && !s.is(Items.FISHING_ROD)) n += s.getCount();
		}
		return n;
	}

	private static String describe(ClientGameTestContext ctx) {
		return ctx.computeOnClient((Minecraft mc) -> {
			StringBuilder sb = new StringBuilder();
			Inventory inv = mc.player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty()) sb.append(s).append(' ');
			}
			return sb.toString();
		});
	}
}
