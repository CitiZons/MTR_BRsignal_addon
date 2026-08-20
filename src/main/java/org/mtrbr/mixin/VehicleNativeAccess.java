package org.mtrbr.mixin;

import org.mtr.core.data.Position;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehiclePosition;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes MTR's native rail block calculation only from the execution mixin. */
@Mixin(Vehicle.class)
public interface VehicleNativeAccess {
	@Invoker(value = "railBlockedDistance", remap = false)
	double mtrbr$invokeRailBlockedDistance(int pathIndex, double railProgress, double brakingDistance, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean includeBlocked, boolean includeReserved);

	@Invoker(value = "getSidingDepartureTime", remap = false)
	long mtrbr$getSidingDepartureTime();
}
