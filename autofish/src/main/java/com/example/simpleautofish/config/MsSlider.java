package com.example.simpleautofish.config;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/** A slider for a millisecond value in [0, max], snapped to {@link #STEP} ms. */
final class MsSlider extends AbstractSliderButton {
	private static final int STEP = 10;

	private final String labelKey;
	private final int max;
	private final IntConsumer onChange;

	MsSlider(int width, String labelKey, int max, int initial, IntConsumer onChange) {
		super(0, 0, width, 20, Component.empty(), (double) initial / max);
		this.labelKey = labelKey;
		this.max = max;
		this.onChange = onChange;
		updateMessage();
	}

	int intValue() {
		return (int) (Math.round(value * max / STEP) * STEP);
	}

	/** Moves the knob without firing the change callback. */
	void setIntValue(int ms) {
		value = Math.clamp((double) ms / max, 0.0, 1.0);
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.translatable(labelKey, intValue()));
	}

	@Override
	protected void applyValue() {
		onChange.accept(intValue());
	}
}
