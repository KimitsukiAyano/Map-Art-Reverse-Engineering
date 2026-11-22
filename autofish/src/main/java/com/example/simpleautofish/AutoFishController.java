package com.example.simpleautofish;

import com.example.simpleautofish.config.AutoFishConfig;
import com.example.simpleautofish.mixin.FishingHookAccessor;
import com.example.simpleautofish.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
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
 * <p>
 * {@link #tick} is called from {@code MinecraftMixin} at the point of the client tick where vanilla handles the
 * use key, and every click goes through vanilla's own {@code Minecraft#startUseItem}, so the packets sent are
 * exactly those of a real right click.
 */
public final class AutoFishController {
	public static final AutoFishController INSTANCE = new AutoFishController();

	/** Minimum durability left before rod protection stops the mod. */
	private static final int PROTECT_DURABILITY = 3;
	/** Give up recasting if the old bobber has not disappeared after this long. */
	private static final long RECAST_TIMEOUT_MS = 5000;

	private enum State { WATCHING, REEL_PENDING, RECAST_PENDING }

	private State state = State.WATCHING;
	private long actionAt;
	private long deadline;
	private InteractionHand hand;

	private AutoFishController() {
	}

	public void tick(Minecraft mc) {
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
				if (hook == null || rodHand(player) != hand) {
					state = State.WATCHING; // reeled in by hand, or rod switched away
					return;
				}
				if (!((FishingHookAccessor) hook).simpleautofish$isBiting()) {
					state = State.WATCHING; // the fish escaped before we reacted; keep waiting
					return;
				}
				if (now < actionAt || !canClick(mc, player, cfg)) {
					return;
				}
				click(mc);
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
				// Wait for the delay, for the old bobber to be gone and (unless allowed) for any open screen to close.
				if (now < actionAt || player.fishing != null || !canClick(mc, player, cfg)) {
					return;
				}
				ItemStack rod = player.getItemInHand(hand);
				if (!isRod(rod) || rodHand(player) != hand) {
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
				click(mc);
				state = State.WATCHING;
			}
		}
	}

	/** A real right click: vanilla's own use-key handler, including block/entity interaction and the swing. */
	private static void click(Minecraft mc) {
		((MinecraftAccessor) mc).simpleautofish$startUseItem();
	}

	/**
	 * Whether a right click may be done now. Without a screen this mirrors {@code handleKeybinds()}, which does
	 * not call {@code startUseItem()} while an item is being used (eating, drawing a bow...).
	 */
	private static boolean canClick(Minecraft mc, LocalPlayer player, AutoFishConfig cfg) {
		if (mc.gui.screen() != null && !cfg.fishInScreens) {
			return false;
		}
		return !player.isUsingItem();
	}

	/**
	 * The hand holding the rod, but only when a vanilla right click would actually use the rod: vanilla tries
	 * the main hand first, so with the rod in the off hand the main hand must be empty (otherwise the click
	 * could, for example, place the block held in the main hand).
	 */
	private static InteractionHand rodHand(LocalPlayer player) {
		if (isRod(player.getMainHandItem())) return InteractionHand.MAIN_HAND;
		if (player.getMainHandItem().isEmpty() && isRod(player.getOffhandItem())) return InteractionHand.OFF_HAND;
		return null;
	}

	private static boolean isRod(ItemStack stack) {
		return stack.is(Items.FISHING_ROD);
	}
}
