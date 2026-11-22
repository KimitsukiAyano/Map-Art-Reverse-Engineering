package com.example.simpleautofish.mixin;

import com.example.simpleautofish.AutoFishController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/**
	 * Runs right after the place where vanilla handles key presses ({@code handleKeybinds()}, skipped while a
	 * screen is open) and before the level/player tick. A right click done here therefore produces the same
	 * packets in the same order and in the same client tick as a real one:
	 * {@code [UseItemOn/Interact] -> UseItem -> Swing -> player movement -> ClientTickEnd}.
	 * (Only reached while a level is loaded and the game is not paused.)
	 */
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"))
	private void simpleautofish$afterKeybinds(CallbackInfo ci) {
		AutoFishController.INSTANCE.tick((Minecraft) (Object) this);
	}
}
