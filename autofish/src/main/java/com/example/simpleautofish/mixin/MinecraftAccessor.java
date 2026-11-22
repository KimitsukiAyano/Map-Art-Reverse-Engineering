package com.example.simpleautofish.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	/** The exact method vanilla runs for a right click (use key). */
	@Invoker("startUseItem")
	void simpleautofish$startUseItem();
}
