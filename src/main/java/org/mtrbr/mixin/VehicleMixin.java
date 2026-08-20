package org.mtrbr.mixin;

import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtrbr.server.MovementGate;
import org.mtrbr.server.SectionStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Vehicle.class)
public abstract class VehicleMixin {

	@Inject(method = "simulate", at = @At("HEAD"), remap = false)
	private void mtrbr$applyMovementGate(CallbackInfo callbackInfo) {
		SectionStateManager.prepareVehicle((Vehicle) (Object) this);
		MovementGate.beforeVehicleSimulation((Vehicle) (Object) this);
	}

	@Redirect(
			method = "simulateMoving",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/VehicleExtraData;setStoppingPoint(D)V"),
			remap = false
	)
	private void mtrbr$clampStoppingPointWrite(VehicleExtraData extraData, double stoppingPoint) {
		((VehicleExtraDataAccess) extraData).mtrbr$setStoppingPoint(MovementGate.clampStoppingPoint((Vehicle) (Object) this, stoppingPoint));
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
			remap = false
	)
	private double mtrbr$clampStoppingPointLocal(double mtrStoppingPoint) {
		return MovementGate.clampStoppingPoint((Vehicle) (Object) this, mtrStoppingPoint);
	}

	@Redirect(
			method = "simulateMoving",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Vehicle;railBlockedDistance(IDDLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;ZZ)D"),
			remap = false
	)
	private double mtrbr$manualOverrideNativeBlock(Vehicle vehicle, int pathIndex, double railProgress, double brakingDistance, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean includeBlocked, boolean includeReserved) {
		// MTR 原生 railBlockedDistance 与本 addon 的闭塞/授权系统冲突：已授权列车会被它
		// 挡在控制边界前，未授权列车又被它提前挡住，导致列车不发车、信号与执行不一致。
		// 这里完全禁用原生阻塞，停车/减速统一由 MovementGate 按 Authorization 控制。
		return -1;
	}

	@Inject(method = "simulate", at = @At("TAIL"), remap = false)
	private void mtrbr$enforceMovementGate(CallbackInfo callbackInfo) {
		MovementGate.enforce((Vehicle) (Object) this);
	}

	@Inject(method = "simulate", at = @At("TAIL"), remap = false)
	private void mtrbr$observeVehicle(CallbackInfo callbackInfo) {
		SectionStateManager.observeVehicle((Vehicle) (Object) this);
	}
}
