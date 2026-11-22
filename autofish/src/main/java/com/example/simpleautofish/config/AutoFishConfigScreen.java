package com.example.simpleautofish.config;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settings screen (opened from Mod Menu). Edits a working copy; "Done" saves it, "Cancel" throws it away.
 */
public final class AutoFishConfigScreen extends Screen {
	private static final int FULL = 310;
	private static final int HALF = 150;
	private static final int GAP = 10;

	private final Screen parent;
	private final AutoFishConfig work = AutoFishConfig.get().copy();
	private HeaderAndFooterLayout layout;

	public AutoFishConfigScreen(Screen parent) {
		super(Component.translatable("simpleautofish.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this);
		layout.addTitleHeader(title, font);

		LinearLayout rows = layout.addToContents(LinearLayout.vertical().spacing(4));

		rows.addChild(pair(
				toggle("simpleautofish.config.enabled", HALF, work.enabled, v -> work.enabled = v),
				toggle("simpleautofish.config.auto_recast", HALF, work.autoRecast, v -> work.autoRecast = v)));
		rows.addChild(toggle("simpleautofish.config.protect_rod", FULL, work.protectRod, v -> work.protectRod = v));

		// ---- reaction time ----
		rows.addChild(modeButton("simpleautofish.config.reaction_mode", () -> work.reactionMode, m -> work.reactionMode = m));
		rows.addChild(timingRow(work.reactionMode, AutoFishConfig.REACTION_MAX_MS,
				"simpleautofish.config.reaction", "simpleautofish.config.auto_range.reaction",
				new int[]{work.reactionFixedMs, work.reactionMinMs, work.reactionMaxMs},
				v -> work.reactionFixedMs = v, v -> work.reactionMinMs = v, v -> work.reactionMaxMs = v));

		// ---- recast delay ----
		rows.addChild(modeButton("simpleautofish.config.recast_mode", () -> work.recastMode, m -> work.recastMode = m));
		rows.addChild(timingRow(work.recastMode, AutoFishConfig.RECAST_MAX_MS,
				"simpleautofish.config.recast", "simpleautofish.config.auto_range.recast",
				new int[]{work.recastFixedMs, work.recastMinMs, work.recastMaxMs},
				v -> work.recastFixedMs = v, v -> work.recastMinMs = v, v -> work.recastMaxMs = v));

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(GAP));
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose()).width(HALF).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(HALF).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private void saveAndClose() {
		AutoFishConfig live = AutoFishConfig.get();
		live.copyFrom(work);
		live.save();
		onClose();
	}

	// ---------------------------------------------------------------- widgets

	private static LinearLayout pair(net.minecraft.client.gui.layouts.LayoutElement a,
									 net.minecraft.client.gui.layouts.LayoutElement b) {
		LinearLayout row = LinearLayout.horizontal().spacing(GAP);
		row.addChild(a);
		row.addChild(b);
		return row;
	}

	private static CycleButton<Boolean> toggle(String key, int width, boolean initial, Consumer<Boolean> set) {
		CycleButton<Boolean> b = CycleButton.onOffBuilder(initial)
				.create(0, 0, width, 20, Component.translatable(key), (btn, v) -> set.accept(v));
		b.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
		return b;
	}

	private CycleButton<TimingMode> modeButton(String key, Supplier<TimingMode> get, Consumer<TimingMode> set) {
		CycleButton<TimingMode> b = CycleButton.builder(TimingMode::label, get.get())
				.withValues(TimingMode.values())
				.withTooltip(v -> Tooltip.create(v.tooltip()))
				.create(0, 0, FULL, 20, Component.translatable(key), (btn, v) -> {
					set.accept(v);
					rebuildWidgets(); // show the sliders that belong to the new mode
				});
		return b;
	}

	/**
	 * The value row under a mode button: one slider (fixed), min/max sliders (range),
	 * or an inactive label describing the built-in range (auto).
	 *
	 * @param values {fixed, min, max}
	 */
	private static net.minecraft.client.gui.layouts.LayoutElement timingRow(
			TimingMode mode, int max, String key, String autoKey, int[] values,
			Consumer<Integer> setFixed, Consumer<Integer> setMin, Consumer<Integer> setMax) {
		return switch (mode) {
			case FIXED -> new MsSlider(FULL, key + ".fixed", max, values[0], setFixed::accept);
			case RANDOM_RANGE -> {
				MsSlider[] s = new MsSlider[2];
				s[0] = new MsSlider(HALF, key + ".min", max, values[1], v -> {
					setMin.accept(v);
					if (s[1].intValue() < v) { // keep min <= max
						s[1].setIntValue(v);
						setMax.accept(v);
					}
				});
				s[1] = new MsSlider(HALF, key + ".max", max, values[2], v -> {
					setMax.accept(v);
					if (s[0].intValue() > v) {
						s[0].setIntValue(v);
						setMin.accept(v);
					}
				});
				yield pair(s[0], s[1]);
			}
			case RANDOM_AUTO -> {
				Button info = Button.builder(Component.translatable(autoKey), b -> {}).width(FULL).build();
				info.active = false;
				yield info;
			}
		};
	}
}
