package org.mtrbr.server;

import org.mtr.core.data.Vehicle;
import java.util.HashMap;
import java.util.Map;
import org.mtrbr.mixin.VehicleAccess;
import org.mtrbr.mixin.VehicleExtraDataAccess;

/** Server-side stopping-point clamp for managed vehicles without authorization. */
public final class MovementGate {
	private static final double EPSILON = 1.0E-6;
	private static final Map<Long, Double> LAST_ENFORCED_BOUNDARY = new HashMap<>();

	private MovementGate() {
	}

	public static void clear(long vehicleId) {
		LAST_ENFORCED_BOUNDARY.remove(vehicleId);
	}

	public static void clearAll() {
		LAST_ENFORCED_BOUNDARY.clear();
	}

	public static void beforeVehicleSimulation(Vehicle vehicle) {
		// Enforce before MTR advances as well as at tick end. The stopping-point
		// hook handles braking; this closes the one-tick gap for a vehicle which
		// was already at or beyond an unauthorized boundary when simulation starts.
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		if (simulator != null && enforceInvalidJunction(vehicle)) return;
		if (simulator != null && RouteRequestManager.isTurnbackHandoff(simulator, vehicle.getId())) {
			RouteRequestManager.logNativeTurnbackActivityGateDeferral(simulator, vehicle.getId());
			return;
		}
		enforce(vehicle);
	}

	public static double clampStoppingPoint(Vehicle vehicle, double mtrStoppingPoint) {
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		if (simulator == null) {
			return mtrStoppingPoint;
		}
		// Physical path validity is independent of authorization, dispatch override and native handoff.
		mtrStoppingPoint = clampToPathBoundary(mtrStoppingPoint, ((VehicleAccess) vehicle).mtrbr$getRailProgress(),
				PathSnapshot.from(vehicle).getInvalidJunctionBoundary());
		// MTR owns stopping-point, speed and path reversal between native terminal
		// entry and a newly authorized Activity. Do not write an old-direction stop.
		if (RouteRequestManager.isTurnbackHandoff(simulator, vehicle.getId())) {
			RouteRequestManager.logNativeTurnbackActivityGateDeferral(simulator, vehicle.getId());
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
		// The moving redirect removes MTR's independent block veto while this addon
		// owns a prefix, so this is MTR's planned station/terminal point. Keep it
		// unless the current Authorization boundary is genuinely earlier.
		final double authorizationLimit = Math.max(head, boundary);
		return Math.min(mtrStoppingPoint, authorizationLimit);
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
		if (enforceInvalidJunction(vehicle)) return;
		// During a recognized native terminal handoff, MTR alone owns railProgress,
		// speed and its stopping point. This is a bounded state, not a generic
		// INVALID_ACTIVITY bypass; normal enforcement resumes on activity ready or timeout.
		if (RouteRequestManager.isTurnbackHandoff(simulator, vehicle.getId())) {
			RouteRequestManager.logNativeTurnbackActivityGateDeferral(simulator, vehicle.getId());
			return;
		}
		double boundary = RouteRequestManager.getStopBoundary(simulator, vehicle.getId());
		final double overrideBoundary = RouteRequestManager.getOneShotOverrideBoundary(simulator, vehicle.getId());
		if (Double.isFinite(overrideBoundary)) boundary = overrideBoundary;
		if (Double.isNaN(boundary)) {
			return;
		}
		final double head = ((VehicleAccess) vehicle).mtrbr$getRailProgress();
		if (boundary < head - EPSILON) {
			if (!RouteRequestManager.isFixedUnauthorisedGateBoundary(simulator, vehicle.getId())) {
				final double lastSafeBoundary = RouteRequestManager.getLastSafeBoundary(simulator, vehicle.getId());
				System.out.println("[MTRBR-GATE-INVALID-BOUNDARY] vehicle=" + vehicle.getId()
						+ " headDistance=" + String.format("%.3f", head)
						+ " boundary=" + String.format("%.3f", boundary)
						+ " lastSafeBoundary=" + String.format("%.3f", lastSafeBoundary)
						+ " source=MOVEMENT_GATE");
				boundary = Math.max(head, Double.isFinite(lastSafeBoundary) ? lastSafeBoundary : head);
			}
		}
		if (head >= boundary - 1e-6) {
			if (head > boundary) {
				((VehicleAccess) vehicle).mtrbr$setRailProgress(boundary);
			}
			((VehicleAccess) vehicle).mtrbr$setSpeed(0);
			((VehicleExtraDataAccess) vehicle.vehicleExtraData).mtrbr$setSpeedTarget(0);
			final Double previous = LAST_ENFORCED_BOUNDARY.put(vehicle.getId(), boundary);
			if (previous == null || Math.abs(previous - boundary) > 1e-6) {
			}
		}
	}

	static double clampToPathBoundary(double stoppingPoint, double head, double invalidBoundary) {
		if (!Double.isFinite(invalidBoundary)) return stoppingPoint;
		// Stop on the incoming rail. Never rewind a vehicle already beyond a legacy bad node.
		return Math.min(stoppingPoint, Math.max(head, invalidBoundary - EPSILON));
	}

	/** Also veto native startUp at the bad node; a valid BR prefix cannot authorize invalid geometry. */
	public static boolean isInvalidJunctionBlocked(Vehicle vehicle) {
		if (SectionStateManager.getCurrentSimulator() == null) return false;
		final double boundary = PathSnapshot.from(vehicle).getInvalidJunctionBoundary();
		return Double.isFinite(boundary) && ((VehicleAccess) vehicle).mtrbr$getRailProgress() >= boundary - EPSILON;
	}

	private static boolean enforceInvalidJunction(Vehicle vehicle) {
		if (!isInvalidJunctionBlocked(vehicle)) return false;
		((VehicleAccess) vehicle).mtrbr$setSpeed(0);
		((VehicleExtraDataAccess) vehicle.vehicleExtraData).mtrbr$setSpeedTarget(0);
		return true;
	}

	/** Native signal/reservation occupancy is not a second authority for an authorized vehicle. */
	public static boolean shouldDisableNativeBlock(Vehicle vehicle) {
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		return simulator != null && (hasStableAuthorizationForNativeBypass(simulator, vehicle)
				|| RouteRequestManager.isTurnbackHandoff(simulator, vehicle.getId()));
	}

	private static boolean hasStableAuthorizationForNativeBypass(org.mtr.core.simulation.Simulator simulator, Vehicle vehicle) {
		if (!RouteRequestManager.hasAuthorization(simulator, vehicle.getId())) return false;
		final RouteRequestManager.GateBoundaryInfo info = RouteRequestManager.getGateBoundaryInfo(simulator, vehicle.getId());
		if (!Double.isFinite(info.activityEnd())) return false;
		final org.mtrbr.mixin.VehicleAccess access = (org.mtrbr.mixin.VehicleAccess) vehicle;
		return access.mtrbr$getRailProgress() < info.activityEnd() - 1.0E-6;
	}

	/** Same ownership rule at MTR's simulateStopped() startUp/reversal checks. */
	public static boolean shouldBypassNativeStoppedBlock(Vehicle vehicle) {
		// At a genuine MTR terminal, the opposite-rail hop happens inside
		// simulateStopped() before the new direction can be authorized. This is not
		// forward permission: it lets MTR complete its own terminal transition.
		final org.mtr.core.simulation.Simulator simulator = SectionStateManager.getCurrentSimulator();
		return shouldDisableNativeBlock(vehicle) || simulator != null && RouteRequestManager.isTurnbackHandoff(simulator, vehicle.getId());
	}
}
