package org.mtrbr.mixin;

import org.mtr.core.data.Data;
import org.mtrbr.server.SectionStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Data.class)
public abstract class DataMixin {

	@Inject(method = "sync", at = @At("TAIL"), remap = false)
	private void mtrbr$topologyChanged(CallbackInfo callbackInfo) {
		SectionStateManager.onTopologySync((Data) (Object) this);
	}
}
