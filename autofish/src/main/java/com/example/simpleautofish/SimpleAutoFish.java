package com.example.simpleautofish;

import com.example.simpleautofish.config.AutoFishConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SimpleAutoFish implements ClientModInitializer {
	public static final String MOD_ID = "simpleautofish";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static KeyMapping toggleKey;
	private final AutoFishController controller = new AutoFishController();

	@Override
	public void onInitializeClient() {
		AutoFishConfig.get(); // load (and create) the config file early

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.simpleautofish.toggle", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
	}

	private void onEndTick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			AutoFishConfig cfg = AutoFishConfig.get();
			cfg.enabled = !cfg.enabled;
			cfg.save();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.translatable(
						cfg.enabled ? "simpleautofish.message.enabled" : "simpleautofish.message.disabled"));
			}
		}
		controller.tick(mc);
	}
}
