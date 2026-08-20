package org.mtrbr.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Runtime access to MTR's simulation-owned VehicleSchema state. */
@Mixin(VehicleSchema.class)
public interface VehicleAccess {

	@Accessor(value = "railProgress", remap = false)
	double mtrbr$getRailProgress();

	@Accessor(value = "railProgress", remap = false)
	void mtrbr$setRailProgress(double railProgress);

	@Accessor(value = "speed", remap = false)
	double mtrbr$getSpeed();

	@Accessor(value = "speed", remap = false)
	void mtrbr$setSpeed(double speed);

}
