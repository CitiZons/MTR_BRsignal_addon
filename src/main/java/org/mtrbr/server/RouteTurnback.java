package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.List;

import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.TraversalContext;
import org.mtrbr.server.RouteRuntime.TurnbackIntent;
import org.mtrbr.server.RouteRuntime.TurnbackPhase;
import org.mtrbr.server.RouteRuntime.TurnbackPhaseFence;
import org.mtrbr.server.RouteRuntime.VehicleState;
import static org.mtrbr.server.RouteRuntime.STATES;

/** Native MTR reversal observation, directed occurrence fences and activity handoff coordination. */
final class RouteTurnback {
	private RouteTurnback() {}

	static void ensureTraversalContext(VehicleState vehicle) {
		if (vehicle.traversalContext == null || !vehicle.traversalContext.pathFingerprint().equals(vehicle.path.getFingerprint())) {
			vehicle.traversalContext = new TraversalContext(vehicle.path.getFingerprint(), vehicle.vehicle.getReversed(),
					vehicle.vehicle.vehicleExtraData.getStopIndex(), vehicle.vehicle.vehicleExtraData.getIsTerminating(), false);
		}
	}

	/**
	 * The immutable path contains both legs of a planned turnback. A scalar end
	 * distance alone cannot distinguish the current leg from the later reversed
	 * occurrence, so every authorization/display scan is fenced by traversal
	 * occurrence before it is distance-limited.
	 */
	static TurnbackPhaseFence turnbackPhaseFence(Simulator simulator, VehicleState vehicle, double referenceDistance, String source) {
		final PathSnapshot path = vehicle.path;
		final int currentIndex = RouteMath.currentTraversalIndex(path, Math.max(0, referenceDistance));
		final TurnbackIntent intent = vehicle.turnbackIntent;
		final PathSnapshot.TurnbackWindow window = intent != null && intent.matches(path) && !intent.reversalConfirmed()
				? intent.window() : path.getNextTurnbackWindow(Math.max(0, referenceDistance));
		int fenceIndex = Integer.MAX_VALUE;
		double fenceDistance = Double.POSITIVE_INFINITY;
		if (intent != null && intent.matches(path) && !intent.reversalConfirmed()) {
			fenceIndex = intent.fenceTraversalIndex();
			// The fence separates the current path occurrence from the future
			// reversed occurrence. A scheduled platform stop inside that leg is not
			// the fence: after dwell the train must still be able to reach the real
			// current-direction endpoint.
			fenceDistance = intent.window().endDistance();
		} else if (window.requiresTurnback()) {
			for (final PathSnapshot.PathTraversal traversal : path.getTraversals()) {
				if (traversal.index() >= currentIndex && traversal.endDistance() <= window.endDistance() + 1.0E-6) {
					fenceIndex = traversal.index();
				}
			}
			if (fenceIndex != Integer.MAX_VALUE) {
				// fenceIndex is the occurrence limit which excludes the future
				// reversed traversal. It ends at the current-direction endpoint;
				// scheduled platform stops remain independently enforced by the
				// normal stop boundary.
				fenceDistance = window.endDistance();
			}
		}
		final TurnbackPhase phase = vehicle.turnbackHandoff.phase() == TurnbackHandoff.Phase.NATIVE_TERMINATING
				? TurnbackPhase.TERMINATING
				: vehicle.turnbackHandoff.phase() == TurnbackHandoff.Phase.REACQUIRING
				? TurnbackPhase.REACQUIRING
				: vehicle.vehicle != null && vehicle.vehicle.getReversed() ? TurnbackPhase.REVERSED : TurnbackPhase.APPROACHING;
		final int finalFenceIndex = fenceIndex;
		final String occurrence = path.getFingerprint() + "@" + currentIndex + ":reversed="
				+ (vehicle.vehicle != null && vehicle.vehicle.getReversed());
		final List<String> excludedFaces = path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched)
				.filter(face -> face.pathTraversalIndex() > finalFenceIndex)
				.map(face -> face.key().toString()).toList();
		final TurnbackPhaseFence fence = new TurnbackPhaseFence(phase, currentIndex, fenceIndex, fenceDistance, occurrence, excludedFaces);
		logTurnbackPhase(vehicle, fence, source);
		return fence;
	}

	/** Persist the next planned reversal before the head reaches its stop boundary. */
	static void captureTurnbackIntent(Simulator simulator, VehicleState vehicle) {
		if (vehicle.path == null || vehicle.vehicle == null) return;
		if (vehicle.turnbackIntent != null && vehicle.turnbackIntent.matches(vehicle.path)) return;
		vehicle.turnbackIntent = null;
		final PathSnapshot.TurnbackWindow window = vehicle.path.getNextTurnbackWindow(vehicle.head);
		if (!window.requiresTurnback()) return;
		int fenceIndex = -1;
		for (final PathSnapshot.PathTraversal traversal : vehicle.path.getTraversals()) {
			if (traversal.endDistance() <= window.endDistance() + 1.0E-6) fenceIndex = traversal.index();
		}
		if (fenceIndex < 0) return;
		vehicle.turnbackIntent = new TurnbackIntent(vehicle.path.getFingerprint(), vehicle.vehicle.getReversed(), window, fenceIndex, false);
		MtrbrDebugLog.event("MTRBR-TURNBACK-INTENT", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
				+ " phase=APPROACHING direction=" + (vehicle.vehicle.getReversed() ? "REVERSED" : "FORWARD")
				+ " stopDistance=" + RouteMath.fmt(window.stopDistance()) + " endDistance=" + RouteMath.fmt(window.endDistance())
				+ " fenceTraversalIndex=" + fenceIndex);
	}

	static List<PathSnapshot.FaceTraversal> phaseFaces(Simulator simulator, VehicleState vehicle, TurnbackPhaseFence fence) {
		return vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched)
				.filter(face -> face.pathTraversalIndex() <= fence.fenceTraversalIndex())
				.toList();
	}

	static void logTurnbackPhase(VehicleState vehicle, TurnbackPhaseFence fence, String source) {
		if (vehicle.vehicle == null) return;
		final String signature = fence.phase() + "|" + fence.currentOccurrence() + "|" + fence.fenceTraversalIndex()
				+ "|" + fence.excludedFaces();
		if (signature.equals(vehicle.lastTurnbackPhaseSignature)) return;
		vehicle.lastTurnbackPhaseSignature = signature;
		MtrbrDebugLog.event("MTRBR-TURNBACK-PHASE", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
				+ " phase=" + fence.phase() + " currentOccurrence=" + fence.currentOccurrence()
				+ " direction=" + (vehicle.vehicle.getReversed() ? "REVERSED" : "FORWARD")
				+ " fenceDistance=" + RouteMath.fmt(fence.fenceDistance())
				+ " fenceTraversalIndex=" + (fence.fenceTraversalIndex() == Integer.MAX_VALUE ? "<none>" : fence.fenceTraversalIndex())
				+ " excludedFaces=" + fence.excludedFaces() + " source=" + source);
	}

	/** Switches windows only after MTR has actually reversed the vehicle. */

	static void beginTurnbackHandoff(VehicleState vehicle, String phase, String action, String reason) {
		if (!vehicle.turnbackHandoff.begin(SectionStateManager.getCurrentTick())) return;
		logTurnbackActivity(vehicle, RouteDiagnostics.describeActivity(vehicle.activityAuthorization), "<pending>", phase, action, reason);
	}

	static void updateTurnbackHandoffTimeout(Simulator simulator, VehicleState vehicle) {
		final TurnbackHandoff.Timeout timeout = vehicle.turnbackHandoff.checkTimeout(SectionStateManager.getCurrentTick());
		if (timeout == null) return;
		final boolean nativeTerminationTimedOut = timeout.phase() == TurnbackHandoff.Phase.NATIVE_TERMINATING;
		MtrbrDebugLog.event("MTRBR-AUTH-RECOVERY", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
				+ " reason=" + (nativeTerminationTimedOut ? "TURNBACK_NATIVE_TERMINATION_TIMEOUT" : "TURNBACK_NEW_ACTIVITY_TIMEOUT")
				+ " elapsedTicks=" + timeout.elapsedTicks());
		logTurnbackActivity(vehicle, "<pending>", RouteDiagnostics.describeActivity(vehicle.activityAuthorization), "HANDOFF_FAILED",
				"RESUME_NORMAL_GATE", nativeTerminationTimedOut ? "NATIVE_TERMINATION_TIMEOUT" : "NEW_DIRECTION_ACTIVITY_TIMEOUT");
		logTurnbackGateHandoff(vehicle, "RESUME_GATE", "TIMEOUT");
	}

	static void logTurnbackActivity(VehicleState vehicle, String oldActivity, String newActivity, String phase, String action, String reason) {
		final String requestId = vehicle.request == null ? "<none>" : vehicle.request.getRequestId();
		final String authorization = vehicle.authorization == null ? "<none>" : vehicle.authorization.getAuthorizationId();
		MtrbrDebugLog.event("MTRBR-TURNBACK-ACTIVITY", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + requestId + " oldActivity=" + oldActivity + " newActivity=" + newActivity
				+ " phase=" + phase + " action=" + action + " reason=" + reason
				+ " reversed=" + vehicle.vehicle.getReversed() + " path=" + (vehicle.path == null ? "<none>" : vehicle.path.getFingerprint())
				+ " occurrence=" + (vehicle.path == null ? -1 : RouteMath.currentTraversalIndex(vehicle.path, vehicle.head))
				+ " authorization=" + authorization + " gateWritesDeferred=" + isTurnbackHandoffActive(vehicle));
	}

	static String turnbackReacquireFailure(Simulator simulator, VehicleState vehicle) {
		final TurnbackPhaseFence fence = turnbackPhaseFence(simulator, vehicle, vehicle.head, "TURNBACK_REACQUIRE");
		final List<PathSnapshot.FaceTraversal> faces = phaseFaces(simulator, vehicle, fence);
		final PathSnapshot.FaceTraversal nextFace = faces.stream().filter(face -> face.distance() > vehicle.head + 1.0E-6).findFirst().orElse(null);
		if (nextFace == null) return "WAIT_MAPPING";
		final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
		final String blockId = saved.getOccurrenceBlockId(vehicle.path.getFingerprint(), nextFace.key());
		return !blockId.isBlank() && saved.getBoundaryId(blockId).equals(vehicle.path.getNextProtectionBoundary(nextFace, faces).id())
				? "FCFS_DENIED" : "WAIT_MAPPING";
	}

	static void logTurnbackReacquire(Simulator simulator, VehicleState vehicle, String result) {
		logTurnbackReacquire(simulator, vehicle, "<none>", result);
	}

	static void logTurnbackReacquire(Simulator simulator, VehicleState vehicle, String savedBlock, String result) {
		if (!vehicle.turnbackHandoff.reacquirePending() || vehicle.request == null) return;
		final TurnbackPhaseFence fence = turnbackPhaseFence(simulator, vehicle, vehicle.head, "TURNBACK_REACQUIRE");
		final String nextFace = phaseFaces(simulator, vehicle, fence).stream()
				.filter(face -> face.distance() > vehicle.head + 1.0E-6)
				.map(face -> face.key().toString()).findFirst().orElse("<none>");
		MtrbrDebugLog.event("MTRBR-TURNBACK-REACQUIRE", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + vehicle.request.getRequestId() + " head=" + RouteMath.fmt(vehicle.head)
				+ " nextFace=" + nextFace + " savedBlock=" + savedBlock + " result=" + result);
		if ("GRANTED".equals(result)) vehicle.turnbackHandoff.grantReported();
		if (!"GRANTED".equals(result)) logTurnbackGateHandoff(vehicle, "RESUME_GATE", "REACQUIRE_FAILED");
	}

	/** At the turnback stop, let MTR perform its native terminate/door/reverse cycle. */
	static boolean isTurnbackHandoff(Simulator simulator, long vehicleId) {
		return isNativeTurnbackActivityTransition(simulator, vehicleId);
	}

	static boolean isTurnbackHandoffActive(VehicleState vehicle) {
		return vehicle != null && vehicle.turnbackHandoff.active();
	}

	/** True only after native MTR reversal and before the same-tick observer rebuild. */
	static boolean isNativeTurnbackActivityTransition(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null || vehicle.traversalContext == null || vehicle.path == null
				|| vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()) {
			return false;
		}
		final TraversalContext context = vehicle.traversalContext;
		return isTurnbackHandoffActive(vehicle) && context.turnbackBegun()
				&& !vehicle.vehicle.vehicleExtraData.getIsTerminating()
				&& vehicle.vehicle.getReversed() != context.reversed();
	}

	static void logNativeTurnbackActivityGateDeferral(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null) return;
		logTurnbackGateHandoff(vehicle, "DEFER_GATE_WRITES", "NATIVE_DIRECTION_TRANSITION");
	}

	static void logTurnbackGateHandoff(VehicleState vehicle, String action, String reason) {
		if (vehicle == null || vehicle.vehicle == null) return;
		final String signature = action + "|" + reason + "|" + vehicle.turnbackHandoff.phase() + "|"
				+ vehicle.vehicle.getReversed() + "|" + vehicle.authorizationEndDistance;
		if (signature.equals(vehicle.lastTurnbackGateSignature)) return;
		vehicle.lastTurnbackGateSignature = signature;
		MtrbrDebugLog.event("TURNBACK_GATE_HANDOFF", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
				+ " action=" + action + " reason=" + reason
				+ " phase=" + vehicle.turnbackHandoff.phase()
				+ " reversed=" + vehicle.vehicle.getReversed()
				+ " authorization=" + (vehicle.authorization == null ? "<none>" : vehicle.authorization.getAuthorizationId())
				+ " activity=" + RouteDiagnostics.describeActivity(vehicle.activityAuthorization));
	}

	/**
	 * The native terminating flag is set by MTR's own block/stop calculation, so
	 * it cannot be the only condition for handing a terminal platform back to
	 * MTR. Keep the handoff tightly scoped to the final four blocks before a
	 * planned reverse boundary.
	 */
	static boolean isApproachingPlannedTurnback(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return isApproachingPlannedTurnback(vehicle);
	}

	static boolean isApproachingPlannedTurnback(VehicleState vehicle) {
		if (vehicle == null || vehicle.path == null || vehicle.path.isEmpty()) return false;
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(Math.max(0, vehicle.head - 4));
		return turnback.requiresTurnback()
				&& vehicle.head >= turnback.stopDistance() - 4
				&& vehicle.head <= turnback.endDistance() + 4;
	}
}
