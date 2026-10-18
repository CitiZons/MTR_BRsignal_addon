package org.mtrbr.server;

import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtrbr.mixin.SidingAccess;

import java.util.List;
import java.util.Map;

import org.mtrbr.server.RouteRequestManager.ActivityAuthorization;
import org.mtrbr.server.RouteRequestManager.GateBoundaryInfo;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.TurnbackPhaseFence;
import org.mtrbr.server.RouteRuntime.VehicleState;
import static org.mtrbr.server.RetainedProtection.blockLockIds;
import static org.mtrbr.server.RouteRuntime.STATES;

/** Movement boundaries and scoped override permits. Does not arbitrate requests or publish signal aspects. */
final class RouteMovementControl {
	private RouteMovementControl() {}

	/**
	 * The departure window applies only to the first authorization from a siding.
	 * Once a request has received an authorization or entered its running phase,
	 * subsequent prefix extension and recovery must remain in normal FCFS.
	 */
	static boolean isDepartureGuardBlocked(Simulator simulator, VehicleState vehicle) {
		final boolean hasPreviousAuthorization = vehicle.authorization != null || !vehicle.lastAuthorizationId.isBlank();
		final RequestState requestState = vehicle.request == null ? RequestState.NONE : vehicle.request.getState();
		final boolean runningRequest = requestState == RequestState.AUTHORIZED || requestState == RequestState.ACTIVE;
		final boolean initialSidingDeparture = vehicle.inSiding && !hasPreviousAuthorization && !runningRequest;
		final boolean departureWindowOpen = !initialSidingDeparture || isDepartureWindow(simulator, vehicle);
		final boolean blocked = initialSidingDeparture && !departureWindowOpen;
		final String reason;
		if (!vehicle.inSiding) {
			reason = "NOT_IN_SIDING";
		} else if (hasPreviousAuthorization || runningRequest) {
			reason = "RUNNING_REQUEST_BYPASSES_INITIAL_DEPARTURE_WINDOW";
		} else if (blocked) {
			reason = "INITIAL_DEPARTURE_WINDOW_NOT_OPEN";
		} else {
			reason = "INITIAL_DEPARTURE_WINDOW_OPEN";
		}
		MtrbrDebugLog.event("MTRBR-DEPARTURE-GUARD", "vehicle=" + vehicle.vehicle.getId()
				+ " inSiding=" + vehicle.inSiding
				+ " hasPreviousAuthorization=" + hasPreviousAuthorization
				+ " requestState=" + requestState
				+ " blocked=" + blocked
				+ " reason=" + reason);
		return blocked;
	}

	/** 出库时刻表窗口：车库车在预计发车前 10 秒内才允许首次授权出库信号。 */
	static boolean isDepartureWindow(Simulator simulator, VehicleState vehicle) {
		if (!vehicle.inSiding) {
			return true;
		}
		final Siding siding = simulator.sidingIdMap.get(vehicle.vehicle.vehicleExtraData.getSidingId());
		if (siding == null) {
			return true;
		}
		final LongArrayList departures = ((SidingAccess) (Object) siding).mtrbr$getDepartures();
		if (departures == null || departures.isEmpty()) {
			return false;
		}
		final int index = (int) vehicle.vehicle.getDepartureIndex();
		if (index < 0 || index >= departures.size()) {
			return false;
		}
		final long departureTime = departures.getLong(index);
		if (departureTime <= 0) {
			return false;
		}
		return simulator.getCurrentMillis() >= departureTime - 10_000;
	}

	/**
	 * 渐进减速的红灯位置：未授权时取当前控制边界；已授权时取授权末端之后第一个信号。
	 * 所有车辆（含已授权车）都以此为目标点按 MTR 制动曲线渐进减速。无红点（授权覆盖到
	 * 进路终点）时返回 NaN（不限速）。
	 */
	static double getStopBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null) {
			return Double.NaN;
		}
		if (vehicle.path == null || vehicle.path.isEmpty()) {
			// An active BR lifecycle may briefly observe an empty immutablePath while
			// MTR is rebuilding/refeshing it.  Do not hand control back to native MTR
			// in that interval: retain the already proven prefix, or hold at head.
			if (vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()
					&& vehicle.activityAuthorization.endDistance() > vehicle.head + 1.0E-6) {
				return nonRetreatingGateBoundary(vehicle, vehicle.activityAuthorization.endDistance(), "EMPTY_PATH_ACTIVITY");
			}
			if (vehicle.authorization != null && vehicle.authorizationEndDistance > vehicle.head + 1.0E-6) {
				return nonRetreatingGateBoundary(vehicle, vehicle.authorizationEndDistance, "EMPTY_PATH_AUTHORIZATION");
			}
			if (vehicle.request != null || vehicle.managed) {
				return vehicle.head;
			}
			return Double.NaN;
		}
		// MTR has already flipped direction in this simulation tick, but the TAIL
		// observer has not yet rebuilt the new-direction ActivityAuthorization.
		// Defer the gate for this exact handoff tick; the next tick is gated normally.
		if (RouteTurnback.isNativeTurnbackActivityTransition(simulator, vehicleId)) {
			return Double.NaN;
		}
		if (vehicle.request != null && (vehicle.request.getState() == RequestState.INVALID || vehicle.request.getState() == RequestState.REVOKED)) {
			return vehicle.head;
		}
		if (RouteTurnback.isTurnbackHandoff(simulator, vehicleId)) {
			return Double.NaN;
		}

		final ServerAspectManager.FaceSnapshot faceSnapshot = ServerAspectManager.getFaceSnapshot(simulator.dimension);
		if (faceSnapshot.faces().isEmpty()) {
			return authorizationRecoveryBoundary(vehicle, "FACE_SNAPSHOT_UNAVAILABLE");
		}
		final ActivityAuthorization activity = vehicle.activityAuthorization;
		if (vehicle.authorization != null && activity != null && activity.valid()) {
			return nonRetreatingGateBoundary(vehicle, authorizedControlBoundary(simulator, vehicle, activity, faceSnapshot), "ACTIVITY_AUTHORIZATION");
		}
		if (vehicle.request != null && (vehicle.request.getState() == RequestState.ACTIVE || vehicle.request.getState() == RequestState.AUTHORIZED || vehicle.authorizationRetryPending)) {
			return authorizationRecoveryBoundary(vehicle, "ACTIVITY_UNAVAILABLE");
		}
		if (vehicle.request != null && vehicle.authorization == null && isAwaitingAuthorization(vehicle.request.getState())) {
			return fixedUnauthorisedGateBoundary(simulator, vehicle);
		}
		return vehicle.request == null
				? (vehicle.managed ? nonRetreatingGateBoundary(vehicle, RouteMath.finiteOr(vehicle.controlDistance, vehicle.head), "CONTROL_UNPROVEN") : Double.NaN)
				: nonRetreatingGateBoundary(vehicle, vehicle.controlDistance, "CONTROL_DISTANCE");
	}

	/** A denied/waiting request must stop at one stable physical face, never chase head each tick. */
	static double fixedUnauthorisedGateBoundary(Simulator simulator, VehicleState vehicle) {
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, vehicle.head, "GATE_UNAUTHORISED_HOLD");
		// FCFS retry may momentarily transition DENIED -> CHECKING -> DENIED.
		// That is not a new authority and must not ratchet this red boundary forward.
		final String signature = vehicle.request.getRequestId() + "|"
				+ vehicle.path.getFingerprint() + "|" + vehicle.vehicle.getReversed();
		if (signature.equals(vehicle.fixedGateBoundarySignature) && Double.isFinite(vehicle.fixedGateBoundary)) {
			return vehicle.fixedGateBoundary;
		}
		final double boundary = RouteTurnback.phaseFaces(simulator, vehicle, fence).stream()
				.filter(face -> face.distance() > vehicle.head + 1.0E-6)
				.mapToDouble(PathSnapshot.FaceTraversal::distance).findFirst().orElse(vehicle.head);
		vehicle.fixedGateBoundarySignature = signature;
		vehicle.fixedGateBoundary = boundary;
		MtrbrDebugLog.event("MTRBR-GATE-UNAUTHORISED-HOLD", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + vehicle.request.getRequestId() + " state=" + vehicle.request.getState()
				+ " head=" + RouteMath.fmt(vehicle.head) + " boundary=" + RouteMath.fmt(boundary)
				+ " reason=" + (boundary > vehicle.head + 1.0E-6 ? "NEXT_FACE" : "NO_FACE_AHEAD"));
		return boundary;
	}

	static boolean isAwaitingAuthorization(RequestState state) {
		return state == RequestState.DENIED || state == RequestState.WAITING || state == RequestState.CHECKING;
	}

	static boolean isFixedUnauthorisedGateBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.request != null && vehicle.authorization == null
				&& isAwaitingAuthorization(vehicle.request.getState())
				&& Double.isFinite(vehicle.fixedGateBoundary)
				&& !vehicle.fixedGateBoundarySignature.isBlank();
	}

	/** Falls back to the currently locked prefix when Activity projection is temporarily unavailable. */
	static double authorizationRecoveryBoundary(VehicleState vehicle, String source) {
		if (vehicle.authorization != null && vehicle.authorizationEndDistance > vehicle.head + 1.0E-6) {
			return nonRetreatingGateBoundary(vehicle, vehicle.authorizationEndDistance, source);
		}
		// No locked prefix remains. Hold at the current head until the FCFS retry
		// creates one; never fall back to a historical control distance.
		return vehicle.request == null ? Double.NaN : vehicle.head;
	}

	static double getLastSafeBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle == null ? Double.NaN : vehicle.lastSafeBoundary;
	}

	static double nonRetreatingGateBoundary(VehicleState vehicle, double boundary, String source) {
		if (boundary >= vehicle.head - 1.0E-6) return boundary;
		final double lastSafeBoundary = RouteMath.finiteOr(vehicle.lastSafeBoundary, vehicle.head);
		final double corrected = Math.max(vehicle.head, lastSafeBoundary);
		System.out.println("[MTRBR-GATE-INVALID-BOUNDARY] vehicle=" + vehicle.vehicle.getId()
				+ " headDistance=" + RouteMath.fmt(vehicle.head) + " boundary=" + RouteMath.fmt(boundary)
				+ " lastSafeBoundary=" + RouteMath.fmt(vehicle.lastSafeBoundary) + " source=" + source);
		return corrected;
	}

	static double authorizedControlBoundary(Simulator simulator, VehicleState vehicle, ActivityAuthorization activity, ServerAspectManager.FaceSnapshot topology) {
		final List<PathSnapshot.FaceTraversal> faces = vehicle.path.getFaceTraversals(simulator.dimension, topology);
		final double effectiveEnd = Math.min(activity.endDistance(), ShuntSignalPolicy.boundary(simulator.dimension, vehicle.path,
				faces.stream().filter(PathSnapshot::isDirectionMatched).toList(), vehicle.head));
		final var shuntExit = ShuntSignalPolicy.exitAtHead(simulator.dimension, vehicle.path, faces, vehicle.head);
		final boolean crossedPublishedExit = shuntExit != null && vehicle.head > shuntExit.distance() + 1.0E-6
				&& vehicle.publishedShuntExit != null && vehicle.publishedShuntExit.sameIdentity(shuntExit.key())
				&& vehicle.publishedShuntPath.equals(vehicle.path.getFingerprint())
				&& vehicle.publishedShuntRequest.equals(vehicle.request.getRequestId());
		if (shuntExit != null && !crossedPublishedExit && !ServerAspectManager.isClearancePublished(simulator, shuntExit.face(),
				RouteVehicleCodes.getVehicleCode(vehicle.vehicle.getId()), vehicle.authorization.getRevision())) {
			return Math.min(effectiveEnd, vehicle.head);
		}
		final var approachingExit = ShuntSignalPolicy.nextExit(simulator.dimension, vehicle.path, faces, vehicle.head);
		if (approachingExit != null) {
			if (!ServerAspectManager.isClearancePublished(simulator, approachingExit.face(), RouteVehicleCodes.getVehicleCode(vehicle.vehicle.getId()), vehicle.authorization.getRevision())) {
				vehicle.publishedShuntExit = null;
				return Math.min(effectiveEnd, approachingExit.distance());
			}
			// A later prefix extension must not re-stop a train just past a cleared node.
			vehicle.publishedShuntExit = approachingExit.key();
			vehicle.publishedShuntPath = vehicle.path.getFingerprint();
			vehicle.publishedShuntRequest = vehicle.request.getRequestId();
		}
		for (final PathSnapshot.FaceTraversal face : faces) {
			if (!PathSnapshot.isDirectionMatched(face) || face.distance() <= vehicle.head + 1.0E-6) continue;
			if (face.distance() >= effectiveEnd - 1.0E-6) return effectiveEnd;
			final Authorization.BlockAuthorization block = vehicle.authorization.getBlockAuthorizations().stream()
					.filter(candidate -> candidate.faceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key())))
					.filter(candidate -> vehicle.tail < candidate.endDistance() - 1.0E-6)
					.findFirst().orElse(null);
			if (block == null || !isBlockLocked(simulator, block, vehicle.request.getRequestId())) return face.distance();
		}
		return effectiveEnd;
	}

	static boolean isBlockLocked(Simulator simulator, Authorization.BlockAuthorization block, String requestId) {
		final Map<String, SectionStateManager.SectionSnapshot> sections = SectionStateManager.getSections(simulator, block.sectionIds());
		return SectionStateManager.areBlocksReservedAndLockedBy(simulator, blockLockIds(List.of(block)), requestId)
				&& !block.sectionIds().isEmpty() && block.sectionIds().stream()
				.allMatch(sectionId -> sections.containsKey(sectionId)
						&& sections.get(sectionId).reservedBy.contains(requestId)
						&& sections.get(sectionId).lockedBy.contains(requestId));
	}

	static double firstPhysicalSignalBoundary(PathSnapshot path, String dimension, ServerAspectManager.FaceSnapshot topology, double head) {
		double nearest = Double.NaN;
		for (final PathSnapshot.FaceTraversal face : path.getFaceTraversals(dimension, topology)) {
			if (PathSnapshot.isDirectionMatched(face) && face.distance() > head && (Double.isNaN(nearest) || face.distance() < nearest)) {
				nearest = face.distance();
			}
		}
		return nearest;
	}

	/** The red-light boundary is the bound rail node, never the signal block position. */

	/** True only while a valid route prefix is physically reserved and locked. */
	static boolean hasAuthorization(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.authorization != null && vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()
				&& vehicle.request != null
				&& vehicle.request.getState() != RequestState.INVALID
				&& vehicle.request.getState() != RequestState.REVOKED
				&& vehicle.activityAuthorization.endDistance() > vehicle.head;
	}

	static double getActivityEnd(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle == null || vehicle.activityAuthorization == null || !vehicle.activityAuthorization.valid()
				? Double.NaN : vehicle.activityAuthorization.endDistance();
	}

	/** Boundary audit consumed by MovementGate; contains no path-derived signal chain. */
	static GateBoundaryInfo getGateBoundaryInfo(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		final ActivityAuthorization activity = vehicle == null ? null : vehicle.activityAuthorization;
		if (activity == null || !activity.valid()) return new GateBoundaryInfo(Double.NaN, List.of(), "<none>", "INVALID_ACTIVITY");
		final List<String> faces = activity.faceTraversalKeys().stream().map(PathSnapshot.FaceTraversalKey::faceId).distinct().toList();
		return new GateBoundaryInfo(activity.endDistance(), faces, "<activity-end>", "ACTIVITY_END");
	}

	static boolean isManualDrivingOverride(Simulator simulator, long vehicleId) {
		return false;
	}

	/** Whether this vehicle's movement is currently owned by the addon MovementGate. */
	static boolean isManaged(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.managed;
	}
}
