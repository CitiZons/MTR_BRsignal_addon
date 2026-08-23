package org.mtrbr.server;

import org.mtr.core.data.Vehicle;
import java.util.HashMap;
import java.util.Map;
import org.mtrbr.mixin.VehicleAccess;
import org.mtrbr.mixin.VehicleExtraDataAccess;

/** Server-side stopping-point clamp for managed vehicles without authorization. */
public final class MovementGate {
	private static final double EPSILON = 1.0E-6;
	private static final Map<Long, Long> LAST_DEBUG = new HashMap<>();
	private static final Map<Long, Double> LAST_ENFORCED_BOUNDARY = new HashMap<>();

	private MovementGate() {
	}

	public static void beforeVehicleSimulation(Vehicle vehicle) {
		// Enforce before MTR advances as well as at tick end. The stopping-point
		// hook handles braking; this closes the one-tick gap for a vehicle which
		// was already at or beyond an unauthorized boundary when simulation starts.
		enforce(vehicle);
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
		if (boundary < head - EPSILON) {
			// A stale boundary must never become a backwards MTR stopping point.
			return Math.min(mtrStoppingPoint, head);
		}
		// 文档：制动距离属于 Movement Gate 的安全约束。用 MTR 的减速度把停车点
		// 前推到“控制边界 - 所需制动距离”，保证未授权列车在红灯前物理停住；
		// 若已越过安全点则就地停车，绝不越过控制边界。
		// VehicleExtension#getSpeed is a client extension value and is 0 on this
		// simulation path. Read VehicleSchema.speed, the value MTR actually advances.
		final double speed = Math.max(0, ((VehicleAccess) vehicle).mtrbr$getSpeed());
		final double deceleration = vehicle.vehicleExtraData.getDeceleration();
		final double brakingDistance = deceleration > 0 ? (speed * speed) / (2 * deceleration) : 0;
		// The braking point may be behind the current head, but the requested
		// stopping point must never be allowed beyond the physical red boundary.
		// The previous max(head, ...) could return a value greater than boundary
		// once the train had advanced into the signal, leaving enforce() to pull it
		// back one tick later.
		final double safetyBoundary = Math.min(boundary - 1.0E-6, Math.max(head, boundary - brakingDistance));
		final double clamped = Math.min(mtrStoppingPoint, safetyBoundary);
		final long now = System.currentTimeMillis();
		final Long lastDebug = LAST_DEBUG.get(vehicle.getId());
		if (lastDebug == null || now - lastDebug >= 5000) {
			LAST_DEBUG.put(vehicle.getId(), now);
			System.out.println("[MTRBR-GATE] vehicle=" + vehicle.getId()
					+ " head=" + String.format("%.1f", head)
					+ " speed=" + String.format("%.1f", speed)
				+ " braking=" + String.format("%.1f", brakingDistance)
				+ " activityEnd=" + String.format("%.1f", RouteRequestManager.getActivityEnd(simulator, vehicle.getId()))
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
		double boundary = RouteRequestManager.getStopBoundary(simulator, vehicle.getId());
		if (Double.isNaN(boundary)) {
			return;
		}
		final double head = ((VehicleAccess) vehicle).mtrbr$getRailProgress();
		if (boundary < head - EPSILON) {
			final double lastSafeBoundary = RouteRequestManager.getLastSafeBoundary(simulator, vehicle.getId());
			System.out.println("[MTRBR-GATE-INVALID-BOUNDARY] vehicle=" + vehicle.getId()
					+ " headDistance=" + String.format("%.3f", head)
					+ " boundary=" + String.format("%.3f", boundary)
					+ " lastSafeBoundary=" + String.format("%.3f", lastSafeBoundary)
					+ " source=MOVEMENT_GATE");
			boundary = Math.max(head, Double.isFinite(lastSafeBoundary) ? lastSafeBoundary : head);
		}
		if (head >= boundary - 1e-6) {
			if (head > boundary) {
				((VehicleAccess) vehicle).mtrbr$setRailProgress(boundary);
			}
			((VehicleAccess) vehicle).mtrbr$setSpeed(0);
			((VehicleExtraDataAccess) vehicle.vehicleExtraData).mtrbr$setSpeedTarget(0);
			final Double previous = LAST_ENFORCED_BOUNDARY.put(vehicle.getId(), boundary);
			if (previous == null || Math.abs(previous - boundary) > 1e-6) {
				final org.mtrbr.server.RouteRequestManager.GateBoundaryInfo info = RouteRequestManager.getGateBoundaryInfo(simulator, vehicle.getId());
				System.out.println("[MTRBR-ENFORCE] vehicle=" + vehicle.getId()
						+ " head=" + String.format("%.1f", head)
						+ " activityEnd=" + String.format("%.1f", info.activityEnd())
						+ " activityFaces=" + info.activityFaces()
						+ " nextSignalCandidate=" + info.nextSignalCandidate()
						+ " stopBoundarySource=" + info.stopBoundarySource()
						+ " finalBoundary=" + String.format("%.1f", boundary));
			}
		}
	}

	public static boolean shouldBypassNativeBlock(Vehicle vehicle) {
		return false;
	}

	/**
	 * 只有当前受本 addon MovementGate 管理的车辆才禁用 MTR 原生阻塞；其余车辆保留 MTR
	 * 原生兜底，避免“全局禁用 + 无 boundary”造成未受控车辆无约束冲过信号。
	 */
	public static boolean shouldDisableNativeBlock(Vehicle vehicle) {
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		if (simulator == null) {
			return false;
		}
		// Re-enable MTR's native block calculation just before a planned terminal
		// platform. That calculation is what raises isTerminating and starts the
		// door/reversal sequence; waiting for the flag first creates a deadlock.
		return RouteRequestManager.hasAuthorization(simulator, vehicle.getId())
				&& !RouteRequestManager.isTurnbackHandoff(simulator, vehicle.getId())
				&& !RouteRequestManager.isApproachingPlannedTurnback(simulator, vehicle.getId());
	}
}
