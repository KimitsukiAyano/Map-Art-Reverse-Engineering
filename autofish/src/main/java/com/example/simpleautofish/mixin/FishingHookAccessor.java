package com.example.simpleautofish.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FishingHook.class)
public interface FishingHookAccessor {
	/** Synced from the server (DATA_BITING): true while a fish is actually hooked. */
	@Accessor("biting")
	boolean simpleautofish$isBiting();
}
