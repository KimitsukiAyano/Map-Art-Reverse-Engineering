package com.example.simpleautofish.config;

import net.minecraft.network.chat.Component;

/** How a delay (reaction time / recast delay) is chosen each time. */
public enum TimingMode {
	/** Always the same value. */
	FIXED,
	/** Built-in, human-like random timing. Nothing to tune. */
	RANDOM_AUTO,
	/** Uniformly random between a user-chosen minimum and maximum. */
	RANDOM_RANGE;

	public Component label() {
		return Component.translatable("simpleautofish.mode." + name().toLowerCase(java.util.Locale.ROOT));
	}

	public Component tooltip() {
		return Component.translatable("simpleautofish.mode." + name().toLowerCase(java.util.Locale.ROOT) + ".tooltip");
	}
}
