package com.example.simpleautofish;

import com.example.simpleautofish.config.AutoFishConfig;
import com.example.simpleautofish.mixin.FishingHookAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Small state machine driven once per client tick:
 * <pre>
 *   WATCHING --(bite seen)--> REEL_PENDING --(reaction time elapsed)--> reel in
 *            <--(fish got away / rod gone)--
 *   reel in --> RECAST_PENDING --(recast delay elapsed)--> cast --> WATCHING
 * </pre>
 * The mod only reels and recasts a rod the player already cast; it never starts fishing on its own.
 */
final class AutoFishController {
	/** Minimum durability left before rod protection stops the mod. */
	private static final int PROTECT_DURABILITY = 3;
	/** Give up recasting if the old bobber has not disappeared after this long. */
	private static final long RECAST_TIMEOUT_MS = 5000;

	private enum State { WATCHING, REEL_PENDING, RECAST_PENDING }

	private State state = State.WATCHING;
	private long actionAt;
	private long deadline;
	private InteractionHand hand;

	void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		AutoFishConfig cfg = AutoFishConfig.get();
		if (player == null || mc.level == null || mc.gameMode == null || !cfg.enabled || player.isSpectator()) {
			state = State.WATCHING;
			return;
		}

		long now = Util.getMillis();
		switch (state) {
			case WATCHING -> {
				FishingHook hook = player.fishing;
				InteractionHand rodHand = rodHand(player);
				if (hook != null && rodHand != null && ((FishingHookAccessor) hook).simpleautofish$isBiting()) {
					hand = rodHand;
					actionAt = now + cfg.nextReactionMs();
					state = State.REEL_PENDING;
				}
			}
			case REEL_PENDING -> {
				FishingHook hook = player.fishing;
				if (hook == null || !isRod(player.getItemInHand(hand))) {
					state = State.WATCHING; // reeled in by hand, or rod switched away
					return;
				}
				if (!((FishingHookAccessor) hook).simpleautofish$isBiting()) {
					state = State.WATCHING; // the fish escaped before we reacted; keep waiting
					return;
				}
				if (now < actionAt || mc.gui.screen() != null) {
					return;
				}
				useRod(mc, player, hand);
				if (cfg.autoRecast) {
					actionAt = now + cfg.nextRecastMs();
					deadline = actionAt + RECAST_TIMEOUT_MS;
					state = State.RECAST_PENDING;
				} else {
					state = State.WATCHING;
				}
			}
			case RECAST_PENDING -> {
				if (now > deadline) {
					state = State.WATCHING;
					return;
				}
				// Wait for the delay, for the old bobber to be gone and for any open GUI to close.
				if (now < actionAt || player.fishing != null || mc.gui.screen() != null) {
					return;
				}
				ItemStack rod = player.getItemInHand(hand);
				if (!isRod(rod)) {
					state = State.WATCHING;
					return;
				}
				if (cfg.protectRod && rod.isDamageableItem()
						&& rod.getMaxDamage() - rod.getDamageValue() <= PROTECT_DURABILITY) {
					cfg.enabled = false;
					cfg.save();
					player.sendOverlayMessage(Component.translatable("simpleautofish.message.rod_protected"));
					state = State.WATCHING;
					return;
				}
				useRod(mc, player, hand);
				state = State.WATCHING;
			}
		}
	}

	/** Same as a normal right click with the item (Minecraft#startUseItem, item branch). */
	private static void useRod(Minecraft mc, LocalPlayer player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		InteractionResult result = mc.gameMode.useItem(player, hand);
		if (result instanceof InteractionResult.Success success) {
			if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
				player.swing(hand, stack.getInteractAnimation(), false);
			}
			player.itemUsed(hand);
		}
	}

	/** The hand vanilla would use the rod from (main hand first), or null. */
	private static InteractionHand rodHand(LocalPlayer player) {
		if (isRod(player.getMainHandItem())) return InteractionHand.MAIN_HAND;
		if (isRod(player.getOffhandItem())) return InteractionHand.OFF_HAND;
		return null;
	}

	private static boolean isRod(ItemStack stack) {
		return stack.is(Items.FISHING_ROD);
	}
}
