package com.example.simpleautofish.test;

import com.example.simpleautofish.config.AutoFishConfig;
import com.example.simpleautofish.config.AutoFishConfigScreen;
import com.example.simpleautofish.config.TimingMode;
import com.example.simpleautofish.mixin.FishingHookAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitFor(mc -> mc.player.fishing != null, 100);
			System.out.println("[autofish-test] cast by hand OK");

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
				int reelTicks = ctx.waitFor(mc -> mc.player.fishing == null, 60);
				ctx.waitFor(mc -> fishCount(mc.player) >= target, 60);
				System.out.println("[autofish-test] catch #" + target + " mode=" + rounds[i][0] + " fixed=" + rounds[i][1]
						+ "ms -> reeled " + reelTicks + " ticks (~" + reelTicks * 50 + " ms) after the bite; inv: " + describe(ctx));
				int recastTicks = ctx.waitFor(mc -> mc.player.fishing != null, 20 * 6); // auto recast
				System.out.println("[autofish-test] recast #" + target + " after " + recastTicks + " ticks");
			}
			ctx.takeScreenshot("fishing");

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
