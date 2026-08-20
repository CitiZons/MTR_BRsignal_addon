package org.mtrbr.server;

import org.mtr.core.data.Vehicle;
import java.util.HashMap;
import java.util.Map;
import org.mtrbr.mixin.VehicleAccess;
import org.mtrbr.mixin.VehicleExtraDataAccess;

/** Server-side stopping-point clamp for managed vehicles without authorization. */
public final class MovementGate {
	private static final Map<Long, Long> LAST_DEBUG = new HashMap<>();

	private MovementGate() {
	}

	public static void beforeVehicleSimulation(Vehicle vehicle) {
		// MTR calculates its stopping point during simulateMoving. The actual
		// enforcement is therefore the ModifyArg hook below, not this tick head.
	}

	public static double clampStoppingPoint(Vehicle vehicle, double mtrStoppingPoint) {
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		if (simulator == null) {
			return mtrStoppingPoint;
		}
		final double boundary = RouteRequestManager.getStopBoundary(simulator, vehicle.getId());
		if (Double.isNaN(boundary)) {
			return mtrStoppingPoint;
		}
		final double head = ((VehicleAccess) vehicle).mtrbr$getRailProgress();
		// 文档：制动距离属于 Movement Gate 的安全约束。用 MTR 的减速度把停车点
		// 前推到“控制边界 - 所需制动距离”，保证未授权列车在红灯前物理停住；
		// 若已越过安全点则就地停车，绝不越过控制边界。
		final double speed = ((VehicleAccess) vehicle).mtrbr$getSpeed();
		final double deceleration = vehicle.vehicleExtraData.getDeceleration();
		final double brakingDistance = deceleration > 0 ? (speed * speed) / (2 * deceleration) : 0;
		final double safetyBoundary = Math.max(head, boundary - brakingDistance);
		final double clamped = Math.min(mtrStoppingPoint, safetyBoundary);
		final long now = System.currentTimeMillis();
		final Long lastDebug = LAST_DEBUG.get(vehicle.getId());
		if (lastDebug == null || now - lastDebug >= 5000) {
			LAST_DEBUG.put(vehicle.getId(), now);
			System.out.println("[MTRBR-GATE] vehicle=" + vehicle.getId()
					+ " head=" + String.format("%.1f", head)
					+ " speed=" + String.format("%.1f", speed)
					+ " braking=" + String.format("%.1f", brakingDistance)
					+ " boundary=" + String.format("%.1f", boundary)
					+ " mtrStop=" + String.format("%.1f", mtrStoppingPoint)
					+ " clamped=" + String.format("%.1f", clamped));
		}
		return clamped;
	}

	/**
	 * 最终安全兜底（文档：Movement Gate 才是实际阻止未授权车辆运行的执行层）：
	 * 在每个模拟 tick 结束时，若车头已经越过红点，直接把 railProgress 拉回红点、
	 * 速度清零，绝不允许多越过任何一个未授权信号。
	 */
	public static void enforce(Vehicle vehicle) {
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		if (simulator == null) {
			return;
		}
		final double boundary = RouteRequestManager.getStopBoundary(simulator, vehicle.getId());
		if (Double.isNaN(boundary)) {
			return;
		}
		final double head = ((VehicleAccess) vehicle).mtrbr$getRailProgress();
		if (head > boundary + 1e-6) {
			((VehicleAccess) vehicle).mtrbr$setRailProgress(boundary);
			((VehicleAccess) vehicle).mtrbr$setSpeed(0);
			((VehicleExtraDataAccess) vehicle.vehicleExtraData).mtrbr$setSpeedTarget(0);
			System.out.println("[MTRBR-ENFORCE] vehicle=" + vehicle.getId()
					+ " head=" + String.format("%.1f", head)
					+ " pulledBack=" + String.format("%.1f", boundary));
		}
	}

	public static boolean shouldBypassNativeBlock(Vehicle vehicle) {
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		return simulator != null && RouteRequestManager.shouldBypassNativeBlock(simulator, vehicle.getId());
	}
}
