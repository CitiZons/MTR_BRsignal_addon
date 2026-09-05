package org.mtrbr.mixin;

import org.mtr.core.data.Depot;
import org.mtrbr.web.DepotPathEditorService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies a persisted editor path without replacing MTR's depot generation flow. */
@Mixin(Depot.class)
public abstract class DepotManualPathMixin {
	@Inject(method = "init", at = @At("TAIL"), remap = false)
	private void mtrbr$restoreManualPathAfterLoad(CallbackInfo callbackInfo) {
		DepotPathEditorService.restorePersistedPath((Depot) (Object) this, "LOAD");
	}

	@Redirect(method = "finishGeneratingPath", at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Depot;generatePlatformDirectionsAndWriteDeparturesToSidings()V"), remap = false)
	private void mtrbr$applyManualPathBeforeSidingGeneration(Depot depot) {
		DepotPathEditorService.restorePersistedPath(depot, "PANEL_REFRESH");
		depot.generatePlatformDirectionsAndWriteDeparturesToSidings();
	}
}
