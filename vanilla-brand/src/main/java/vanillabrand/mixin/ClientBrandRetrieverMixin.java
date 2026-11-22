package vanillabrand.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces the client brand (sent to servers in the minecraft:brand payload) to "vanilla".
 * Minecraft 26.x is unobfuscated, so the target is referenced by its Mojang name directly.
 */
@Mixin(targets = "net.minecraft.client.ClientBrandRetriever")
public abstract class ClientBrandRetrieverMixin {
	@Inject(method = "getClientModName", at = @At("HEAD"), cancellable = true, remap = false)
	private static void vanillabrand$forceVanilla(CallbackInfoReturnable<String> cir) {
		cir.setReturnValue("vanilla");
	}
}
