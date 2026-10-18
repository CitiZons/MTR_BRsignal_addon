package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;

import org.mtrbr.server.RouteRuntime.OverrideFace;
import org.mtrbr.server.RouteRuntime.OverridePermit;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.VehicleState;
import static org.mtrbr.server.RouteRuntime.STATES;

/** Scoped one-shot override permits and their expiry; never bypasses physical resource checks. */
final class RouteOverride {
	private RouteOverride() {}
	static boolean canGrantOneShotOverride(VehicleState vehicle) {
		return vehicle != null && vehicle.request != null && !RouteAuthorizationLifecycle.isTerminal(vehicle.request.getState())
				&& vehicle.path != null && !vehicle.path.isEmpty() && vehicle.authorization == null;
	}

	static OverrideFace nextRedOverrideFace(Simulator simulator, VehicleState vehicle) {
		for (final PathSnapshot.FaceTraversal face : vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension))) {
			if (!PathSnapshot.isDirectionMatched(face) || face.distance() <= vehicle.head + 1.0E-6) continue;
			if (ServerAspectManager.get(simulator, face.face().signalPos(), face.face().backSide()) == ServerAspect.RED) {
				return new OverrideFace(face.key(), face.distance());
			}
		}
		return null;
	}

	static void clearOneShotOverride(Simulator simulator, VehicleState vehicle, String reason) {
		if (!vehicle.oneShotOverride && vehicle.overridePermit == null && vehicle.overrideState != OverrideState.ONE_SHOT) return;
		vehicle.oneShotOverride = false;
		vehicle.overridePermit = null;
		vehicle.overrideEndDistance = Double.NaN;
		vehicle.overrideState = OverrideState.NONE;
		final String detail = "vehicleId=" + (vehicle.vehicle == null ? "<none>" : vehicle.vehicle.getId())
				+ " requestId=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId()) + " reason=" + reason;
		MtrbrDebugLog.event("MTRBR-OVERRIDE-CLEAR", detail);
		final State state = STATES.get(simulator);
		if (state != null) state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " override-clear " + detail);
	}

	static void expireOneShotOverride(Simulator simulator, VehicleState vehicle, String reason) {
		clearOneShotOverride(simulator, vehicle, reason);
		if (vehicle.request != null && vehicle.authorization == null
				&& (vehicle.request.getState() == RequestState.ACTIVE || vehicle.request.getState() == RequestState.AUTHORIZED)) {
			vehicle.request.transitionIfChanged(RequestState.CHECKING, "One-shot override boundary reached");
		}
	}

	static boolean hasValidOneShotOverride(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		final OverridePermit permit = vehicle == null ? null : vehicle.overridePermit;
		if (permit == null || vehicle.path == null || vehicle.request == null || vehicle.vehicle == null
				|| RouteAuthorizationLifecycle.isTerminal(vehicle.request.getState())) return false;
		if (permit.type() != OverrideState.ONE_SHOT || permit.vehicleId() != vehicleId || !permit.requestId().equals(vehicle.request.getRequestId())
				|| !permit.pathFingerprint().equals(vehicle.path.getFingerprint()) || permit.topologyRevision() != SectionStateManager.getTopologyRevision(simulator)
				|| !Double.isFinite(permit.endDistance()) || vehicle.head >= permit.endDistance() - 1.0E-6) return false;
		final OverrideFace next = nextRedOverrideFace(simulator, vehicle);
		return next != null && next.key().canonical().equals(permit.signalFaceKey().canonical());
	}

	static double getOneShotOverrideBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (hasValidOneShotOverride(simulator, vehicleId)) return vehicle.overridePermit.endDistance();
		if (vehicle != null && vehicle.overridePermit != null) clearOneShotOverride(simulator, vehicle, "permit validation failed");
		return Double.NaN;
	}

	static double nextBoundNodeDistance(Simulator simulator, VehicleState vehicle) {
		if (vehicle.path == null) {
			return Double.NaN;
		}
		for (final PathSnapshot.FaceTraversal face : vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream().filter(PathSnapshot::isDirectionMatched).toList()) {
			if (face.distance() > vehicle.head + 1.0E-6) {
				return face.distance();
			}
		}
		return Double.NaN;
	}

	static boolean hasOneShotOverride(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && hasValidOneShotOverride(simulator, vehicleId);
	}
}
