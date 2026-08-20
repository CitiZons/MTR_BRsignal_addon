package org.mtrbr.mixin;

import org.mtr.core.data.VehicleExtraData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls MTR's protected simulation setters without reflective access. */
@Mixin(VehicleExtraData.class)
public interface VehicleExtraDataAccess {

	@Invoker(value = "setStoppingPoint", remap = false)
	void mtrbr$setStoppingPoint(double stoppingPoint);

	@Invoker(value = "setSpeedTarget", remap = false)
	void mtrbr$setSpeedTarget(double speedTarget);

	@Invoker(value = "setPowerLevel", remap = false)
	void mtrbr$setPowerLevel(int powerLevel);
}
