package org.mtrbr.server;

import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mtrbr.server.RouteRequestManager.ActivityAuthorization;

/** Simulation-thread request state shared by route services; no client or Web code may mutate it. */
final class RouteRuntime {
	private RouteRuntime() {}

	static final Map<Simulator, State> STATES = new IdentityHashMap<>();

	static final java.util.Set<String> PENDING_MAPPING_RETRIES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	static final class State {
		final Map<Long, VehicleState> vehicles = new HashMap<>();
		final RouteAuditLog audit = new RouteAuditLog();
		long authorizationRevision;
	}

	static final class VehicleState {
		boolean isAuthorizationEffective() {
			return request != null && authorization != null
					&& request.getState() != RequestState.INVALID
					&& request.getState() != RequestState.REVOKED
					&& authorizationEndDistance > head + 1.0E-6;
		}

		void clearFixedGate() {
			fixedGateBoundary = Double.NaN;
			fixedGateBoundarySignature = "";
		}

		boolean hasRetainedControl() {
			return managed || request != null || authorization != null || !retainedProtection.isEmpty();
		}

		Vehicle vehicle;
		PathSnapshot path;
		RouteRequest request;
		Authorization authorization;
		TraversalContext traversalContext;
		Set<String> sections = Set.of();
		double head;
		double tail;
		double controlDistance;
		double endDistance;
		double authorizationLookaheadEndDistance;
		double authorizationEndDistance;
		boolean authorizationRetryPending;
		final TurnbackHandoff turnbackHandoff = new TurnbackHandoff();
		double fixedGateBoundary = Double.NaN;
		PathSnapshot.FaceTraversalKey publishedShuntExit;
		String publishedShuntPath = "", publishedShuntRequest = "";
		String fixedGateBoundarySignature = "";
		TurnbackIntent turnbackIntent;
		String lastAuthorizationId = "";
		double lastSafeBoundary = Double.NaN;
		ActivityAuthorization activityAuthorization;
		ActivityAuthorization lastValidActivity;
		int activityFallbackTicks;
		String lastValidActivityRequestId = "";
		String lastValidActivityPathFingerprint = "";
		long lastValidActivityTopologyRevision = -1;
		String lastActivitySignature = "";
		String lastDirectionAuditFingerprint = "";
		String controlFaceId = "";
		boolean inSiding;
		String sidingDisplay = "";
		String lastRouteName = "";
		String lastRouteDestination = "";
		String lastNextStation = "";
		double lastHead = -1;
		double lastSnapshotHead;
		long lastSnapshotTick;
		long lastPassedSignalMillis;
		long lastNoRangeDebugMillis;
		String lastAuthorizationLookaheadSignature = "";
		String lastTurnbackPhaseSignature = "";
		String lastTurnbackGateSignature = "";
		final Set<String> routeProjectionAuditSignatures = new HashSet<>();
		long generation;
		boolean observed;
		boolean pathEmpty;
		int missingTicks;
		boolean missingUnconfirmedLogged;
		double lastObservedHead = Double.NaN;
		double lastObservedTail = Double.NaN;
		PathSnapshot lastObservedPath;
		long lastObservedTick = -1;
		boolean managed;
		boolean oneShotOverride;
		OverridePermit overridePermit;
		OverrideState overrideState = OverrideState.NONE;
		double overrideEndDistance = Double.NaN;
		final RetainedProtection retainedProtection = new RetainedProtection();
		long lastCheckedStateRevision = -1;
		long lastCheckedTick = -20;
		long lastFaceTopologyRevision = -1;
	}

	record OverrideFace(PathSnapshot.FaceTraversalKey key, double distance) {
	}

	record OverridePermit(OverrideState type, long vehicleId, String requestId, PathSnapshot.FaceTraversalKey signalFaceKey,
			double startDistance, double endDistance, String pathFingerprint, long topologyRevision, long createdTick) {
	}

	record ControlPoint(PathSnapshot.FaceTraversal traversal) {
	}

	record ControlRange(String faceId, double controlDistance, double lookaheadEndDistance, double requestEndDistance, double triggerStart, List<String> signalFaceIds) {
	}

	record Clearance(List<String> sectionIds, List<String> blockIds, List<PathSnapshot.PathTraversal> traversals, List<PathSnapshot.FaceTraversalKey> faceTraversalKeys, List<Authorization.BlockAuthorization> blockAuthorizations, double endDistance) {
	}

	enum TurnbackPhase {
		APPROACHING,
		TERMINATING,
		REVERSED,
		REACQUIRING
	}

	record TurnbackPhaseFence(TurnbackPhase phase, int currentTraversalIndex, int fenceTraversalIndex,
			double fenceDistance, String currentOccurrence, List<String> excludedFaces) {
		TurnbackPhaseFence {
			excludedFaces = List.copyOf(excludedFaces);
		}
	}

	record TurnbackIntent(String pathFingerprint, boolean initialReversed, PathSnapshot.TurnbackWindow window,
			int fenceTraversalIndex, boolean reversalConfirmed) {
		boolean matches(PathSnapshot path) {
			return path != null && pathFingerprint.equals(path.getFingerprint());
		}

		TurnbackIntent withReversalConfirmed() {
			return new TurnbackIntent(pathFingerprint, initialReversed, window, fenceTraversalIndex, true);
		}
	}

	record PhysicalBlockOccupancy(String blockId, String occurrenceId, double startDistance, double endDistance,
			List<String> sectionIds, List<String> junctionResources, PathSnapshot.FaceTraversalKey entryFaceKey) {
		PhysicalBlockOccupancy {
			sectionIds = List.copyOf(sectionIds);
			junctionResources = List.copyOf(junctionResources);
		}

		String physicalKey() {
			return blockId + "|" + entryFaceKey;
		}
	}

	record TraversalContext(String pathFingerprint, boolean reversed, int stopIndex, boolean terminating, boolean turnbackBegun) {
		TraversalContext withTurnbackBegun(boolean terminating) {
			return new TraversalContext(pathFingerprint, reversed, stopIndex, terminating, true);
		}
	}
}
