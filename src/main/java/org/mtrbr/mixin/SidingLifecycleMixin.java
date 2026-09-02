package org.mtrbr.mixin;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtrbr.server.CapacityLeaseManager;
import org.mtrbr.server.RouteRequestManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Reports confirmed MTR siding removals; cleanup is deferred to the simulation tick tail. */
@Mixin(Siding.class)
public abstract class SidingLifecycleMixin {

	@Redirect(method = "clearVehicles", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;clear()V"), remap = false)
	private void mtrbr$clearVehicles(ObjectArraySet<Vehicle> vehicles) {
		queueAndClear(vehicles, "SIDING_CLEAR");
	}

	@Redirect(method = "generateRoute", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;clear()V"), remap = false)
	private void mtrbr$clearVehiclesForRouteRegeneration(ObjectArraySet<Vehicle> vehicles) {
		queueAndClear(vehicles, "ROUTE_REGENERATED");
	}

	@Redirect(method = "simulateTrain", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;clear()V"), remap = false)
	private void mtrbr$clearVehiclesForMissingSiding(ObjectArraySet<Vehicle> vehicles) {
		queueAndClear(vehicles, "SIDING_REMOVED");
	}

	@Redirect(method = "simulateTrain", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;forEach(Ljava/util/function/Consumer;)V"), remap = false)
	private void mtrbr$removeReturnedVehicles(ObjectArraySet<Vehicle> removedVehicles, Consumer<Vehicle> removal) {
		for (final Vehicle vehicle : removedVehicles) {
			removal.accept(vehicle);
			RouteRequestManager.onMtrVehicleRemoved(vehicle, "RETURNED_TO_DEPOT");
		}
	}

	/** Wraps MTR's original predicate without changing its condition, return value, or set operation. */
	@Redirect(method = "updateData", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;removeIf(Ljava/util/function/Predicate;)Z"), remap = false)
	private boolean mtrbr$recordUpdateDataRemovals(ObjectArraySet<Vehicle> vehicles, Predicate<Vehicle> predicate) {
		final List<Vehicle> removed = new ArrayList<>();
		final boolean changed = vehicles.removeIf(vehicle -> {
			final boolean shouldRemove = predicate.test(vehicle);
			if (shouldRemove) removed.add(vehicle);
			return shouldRemove;
		});
		removed.forEach(vehicle -> RouteRequestManager.onMtrVehicleRemoved(vehicle, "SIDING_UPDATE_DATA"));
		return changed;
	}

	/** The third add in simulateTrain is MTR's only new Vehicle construction result. */
	@Redirect(method = "simulateTrain", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;add(Ljava/lang/Object;)Z", ordinal = 2), remap = false)
	private boolean mtrbr$addNewVehicleWithFleetCapacity(ObjectArraySet<Vehicle> vehicles, Object candidate) {
		final Siding siding = (Siding) (Object) this;
		if (!CapacityLeaseManager.canCreateSidingVehicle(siding)) {
			org.mtrbr.server.MtrbrDebugLog.event("MTRBR-SIDING-FLEET", "action=BLOCK_CREATE siding=" + siding.getId() + " maxVehicles=1");
			return false;
		}
		final boolean added = vehicles.add((Vehicle) candidate);
		if (added) CapacityLeaseManager.registerSidingVehicle(siding, (Vehicle) candidate);
		return added;
	}

	private static void queueAndClear(ObjectArraySet<Vehicle> vehicles, String reason) {
		final List<Vehicle> removed = new ArrayList<>(vehicles);
		vehicles.clear();
		removed.forEach(vehicle -> RouteRequestManager.onMtrVehicleRemoved(vehicle, reason));
	}
}
