package vanillabrand.mixin;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.minecraft.resources.Identifier;

/**
 * A vanilla client never announces any custom payload channels. Fabric API announces every channel
 * its mods can receive (e.g. fabric:attachment_sync_v1) through minecraft:register / minecraft:unregister
 * and c:register. Empty those lists so nothing is announced:
 * Fabric API's createRegistrationPayload returns null (= send nothing) for an empty collection.
 */
@Mixin(value = AbstractChanneledNetworkAddon.class, remap = false)
public abstract class ChannelRegistrationMixin {
	@ModifyVariable(method = "createRegistrationPayload", at = @At("HEAD"), argsOnly = true, remap = false)
	private Collection<Identifier> vanillabrand$hideChannels(Collection<Identifier> channels) {
		return Collections.emptyList();
	}

	@Inject(method = "createRegisterPayload", at = @At("RETURN"), cancellable = true, remap = false)
	private void vanillabrand$hideCommonChannels(CallbackInfoReturnable<CommonRegisterPayload> cir) {
		CommonRegisterPayload original = cir.getReturnValue();
		cir.setReturnValue(new CommonRegisterPayload(original.version(), original.protocol(), Set.of()));
	}
}
