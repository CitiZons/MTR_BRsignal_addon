package org.mtrbr.mixin;

import org.mtr.core.data.Position;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehiclePosition;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtrbr.server.MovementGate;
import org.mtrbr.server.SectionStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Vehicle.class)
public abstract class VehicleMixin {

	@Inject(method = "simulate", at = @At("HEAD"), remap = false)
	private void mtrbr$applyMovementGate(CallbackInfo callbackInfo) {
		SectionStateManager.prepareVehicle((Vehicle) (Object) this);
		MovementGate.beforeVehicleSimulation((Vehicle) (Object) this);
	}

	@ModifyArg(
			method = "simulateMoving",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/VehicleExtraData;setStoppingPoint(D)V"),
			index = 0,
			remap = false
	)
	private double mtrbr$clampStoppingPointWrite(double stoppingPoint) {
		return MovementGate.clampStoppingPoint((Vehicle) (Object) this, stoppingPoint);
	}

	/**
	 * MTR 的 simulateMoving 后续速度/停车计算使用的是局部变量 5（LVT 5），
	 * 而不是 VehicleExtraData.stoppingPoint 字段；只 Redirect 字段写入无效。
	 * 这里直接修改 LVT 5，确保 515-709 行的减速与“到达停车点强制停止”真正生效。
	 */
	@ModifyVariable(
			method = "simulateMoving",
			at = @At("STORE"),
			index = 5,
			require = 1,
			expect = 1,
			remap = false
	)
	private double mtrbr$clampStoppingPointLocal(double mtrStoppingPoint) {
		return MovementGate.clampStoppingPoint((Vehicle) (Object) this, mtrStoppingPoint);
	}

	/**
	 * Keep MTR's planned station/terminal point, but remove its independent signal,
	 * reservation and vehicle-block veto while this addon owns a valid prefix.
	 */
	@Redirect(
			method = "simulateMoving",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Vehicle;railBlockedDistance(IDDLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;ZZ)D"),
			remap = false
	)
	private double mtrbr$overrideMovingNativeBlock(Vehicle vehicle, int pathIndex, double railProgress, double brakingDistance, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean includeBlocked, boolean includeReserved) {
		final Vehicle self = (Vehicle) (Object) this;
		if (MovementGate.shouldDisableNativeBlock(self)) return -1;
		return ((VehicleNativeAccess) self).mtrbr$invokeRailBlockedDistance(pathIndex, railProgress, brakingDistance, vehiclePositions, includeBlocked, includeReserved);
	}

	/**
	 * MTR checks the same native blocker before startUp(), including after dwell and
	 * immediately before its native opposite-rail reversal. Only bypass it when the
	 * addon has an outgoing authorization or owns the active terminal handoff.
	 */
	@Redirect(
			method = "simulateStopped",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Vehicle;railBlockedDistance(IDDLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;ZZ)D"),
			remap = false
	)
	private double mtrbr$overrideStoppedNativeBlock(Vehicle vehicle, int pathIndex, double railProgress, double brakingDistance, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean includeBlocked, boolean includeReserved) {
		final Vehicle self = (Vehicle) (Object) this;
		if (MovementGate.shouldBypassNativeStoppedBlock(self)) return -1;
		return ((VehicleNativeAccess) self).mtrbr$invokeRailBlockedDistance(pathIndex, railProgress, brakingDistance, vehiclePositions, includeBlocked, includeReserved);
	}

	@Inject(method = "simulate", at = @At("TAIL"), remap = false)
	private void mtrbr$observeVehicleThenEnforceGate(CallbackInfo callbackInfo) {
		// MTR may set isTerminating or reverse its path during simulate(). Observe
		// that native state before the Gate can write railProgress or speed.
		SectionStateManager.observeVehicle((Vehicle) (Object) this);
		MovementGate.enforce((Vehicle) (Object) this);
	}
}
