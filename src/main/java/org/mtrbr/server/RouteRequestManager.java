package org.mtrbr.server;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.mixin.SidingAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mtrbr.server.RouteRuntime.Clearance;
import org.mtrbr.server.RouteRuntime.ControlRange;
import org.mtrbr.server.RouteRuntime.OverrideFace;
import org.mtrbr.server.RouteRuntime.OverridePermit;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.VehicleState;
import org.mtrbr.server.RouteRuntime.TurnbackIntent;
import org.mtrbr.server.RouteRuntime.TraversalContext;
import static org.mtrbr.server.RouteRuntime.PENDING_MAPPING_RETRIES;
import static org.mtrbr.server.RouteRuntime.STATES;

/** Public route API and simulation-tick coordinator. Delegates planning, lifecycle, gate and snapshot work to server services. */
public final class RouteRequestManager {

	private static final int MISSING_TICK_GRACE = 3;

	/** MTR lifecycle callbacks only enqueue; cleanup runs at Simulator.tick tail. */
	private static final Map<Long, ConfirmedVehicleRemoval> CONFIRMED_MTR_REMOVALS = new java.util.concurrent.ConcurrentHashMap<>();

	private RouteRequestManager() {
	}

	public static void observeVehicle(Vehicle vehicle, double head, double tail, Set<String> occupiedSections) {
		final Simulator simulator = SectionStateManager.getCurrentSimulator();
		if (simulator == null) {
			return;
		}
		final State state = STATES.computeIfAbsent(simulator, ignored -> new State());
		final PathSnapshot path = PathSnapshot.from(vehicle);
		final VehicleState current = state.vehicles.computeIfAbsent(vehicle.getId(), ignored -> new VehicleState());
		final double previousHead = current.head;
		final boolean hadPreviousObservation = current.lastObservedTick >= 0;
		final String previousPathFingerprint = current.path == null ? "" : current.path.getFingerprint();
		final boolean pathChanged = !previousPathFingerprint.isBlank() && !path.isEmpty()
				&& !previousPathFingerprint.equals(path.getFingerprint());
		current.vehicle = vehicle;
		final boolean pathWasEmpty = current.pathEmpty;
		current.path = path;
		current.head = head;
		current.tail = tail;
		current.sections = Set.copyOf(occupiedSections);
		// A Vehicle was observed even when MTR has not populated immutablePath yet.
		// Empty path is a path-readiness state, never evidence that the Vehicle vanished.
		current.observed = true;
		current.missingTicks = 0;
		current.lastObservedHead = head;
		current.lastObservedTail = tail;
		current.lastObservedPath = path;
		current.lastObservedTick = SectionStateManager.getCurrentTick();
		RouteDispatchSnapshots.updateSidingContext(simulator, current);
		CapacityLeaseManager.releaseExitedZones(simulator, vehicle.getId(), current.sections);
		if (path.isEmpty()) {
			if (!current.pathEmpty) {
				MtrbrDebugLog.event("MTRBR-MTR-PATH-EMPTY", "vehicle=" + vehicle.getId()
						+ " headDistance=" + RouteMath.fmt(head) + " request=" + (current.request == null ? "<none>" : current.request.getRequestId())
						+ " action=OBSERVATION_DEFERRED");
			}
			current.pathEmpty = true;
			return;
		}
		if (pathWasEmpty) {
			MtrbrDebugLog.event("MTRBR-MTR-PATH-READY", "vehicle=" + vehicle.getId()
					+ " pathFingerprint=" + path.getFingerprint() + " headDistance=" + RouteMath.fmt(head)
					+ " action=ENTER_OBSERVATION_CHAIN");
		}
		if (pathChanged) {
			MtrbrDebugLog.event("MTRBR-MTR-PATH-CHANGED", "vehicle=" + vehicle.getId()
					+ " oldPathFingerprint=" + previousPathFingerprint
					+ " newPathFingerprint=" + path.getFingerprint()
					+ " headDistance=" + RouteMath.fmt(head) + " source=MTR_IMMUTABLE_PATH");
		}
		current.pathEmpty = false;
		RouteTurnback.ensureTraversalContext(current);

		final ServerAspectManager.FaceSnapshot faceSnapshot = ServerAspectManager.getFaceSnapshot(simulator.dimension);
		final boolean faceTopologyChanged = current.lastFaceTopologyRevision >= 0
				&& current.lastFaceTopologyRevision != faceSnapshot.revision();
		if (faceTopologyChanged) {
			// A request created while the server was still loading signal chunks may
			// have been denied with NO_FACE_TRAVERSAL. Re-enter the normal checking
			// chain when the authoritative face snapshot changes; never hand control
			// back to native MTR merely because the first snapshot was incomplete.
			if (current.authorization != null) {
				RouteAuthorizationLifecycle.invalidateAuthorization(simulator, current, ReleaseReason.INVALID, RequestState.CHECKING);
			} else if (current.request != null && current.request.getState() != RequestState.CHECKING
					&& !RouteAuthorizationLifecycle.isTerminal(current.request.getState())
					&& current.request.canTransitionTo(RequestState.CHECKING)) {
				current.request.transitionIfChanged(RequestState.CHECKING, "Signal topology revision changed");
			}
			current.lastCheckedStateRevision = -1;
			current.lastCheckedTick = -20;
			current.authorizationRetryPending = false;
			current.clearFixedGate();
			MtrbrDebugLog.event("MTRBR-FACE-TOPOLOGY-RECHECK", "vehicle=" + vehicle.getId()
					+ " request=" + (current.request == null ? "<none>" : current.request.getRequestId())
					+ " oldRevision=" + current.lastFaceTopologyRevision + " newRevision=" + faceSnapshot.revision()
					+ " action=RECHECK_CONTROL_RANGE");
		}
		current.lastFaceTopologyRevision = faceSnapshot.revision();

		final List<PathSnapshot.FaceTraversal> faceTraversals = path.getFaceTraversals(simulator.dimension, faceSnapshot);
		for (final PathSnapshot.FaceTraversal faceTraversal : faceTraversals) {
			if (faceTraversal.distance() > current.lastHead && faceTraversal.distance() <= head) {
				current.lastPassedSignalMillis = System.currentTimeMillis();
			}
		}
		current.lastHead = head;
		if (current.overridePermit != null && head >= current.overridePermit.endDistance() - 1.0E-6) {
			RouteOverride.expireOneShotOverride(simulator, current, "head passed override boundary");
		}

		if (!path.matchesTopology(simulator)) {
			RouteDiagnostics.logActivityInvalidation(simulator, current, "Path topology changed before vehicle movement");
			RouteAuthorizationLifecycle.invalidateAuthorization(simulator, current, ReleaseReason.INVALID, RequestState.INVALID);
			RouteOverride.clearOneShotOverride(simulator, current, "topology changed");
			current.managed = true;
			return;
		}
		RouteTurnback.captureTurnbackIntent(simulator, current);
		if (completeTurnbackIfReady(simulator, current)) {
			RouteAuthorizationLifecycle.refreshActivityAuthorization(simulator, current);
		}

		// MTR can restart a repeated depot route at distance zero after the train
		// completes a cycle/turnback while retaining the same immutable path
		// fingerprint. Retire movement authority, but retain physical protection
		// until clearance or same-train ownership handover is confirmed.
		if (hadPreviousObservation && current.request != null && current.authorization != null
				&& current.request.getPathFingerprint().equals(path.getFingerprint())
				&& head + 20.0 < previousHead) {
			final String requestId = current.request.getRequestId();
			MtrbrDebugLog.event("REQUEST-CYCLE-RESET", "vehicle=" + vehicle.getId()
					+ " request=" + requestId + " previousHead=" + String.format("%.1f", previousHead)
					+ " newHead=" + String.format("%.1f", head));
			RouteAuthorizationLifecycle.resetRouteCycle(simulator, current);
		}

		boolean inSiding = false;
		for (final PathSnapshot.PathSection section : path.getSections()) {
			if (head >= section.startDistance() && head <= section.endDistance()) {
				inSiding = section.isSiding();
				break;
			}
		}
		current.inSiding = inSiding;
		current.sidingDisplay = "";
		if (inSiding) {
			final Siding siding = simulator.sidingIdMap.get(vehicle.vehicleExtraData.getSidingId());
			if (siding != null) {
				current.sidingDisplay = siding.getDepotName();
			}
		}

		if (current.authorization != null && current.request != null && !current.request.getPathFingerprint().equals(path.getFingerprint())) {
			RouteDiagnostics.logActivityInvalidation(simulator, current, "MTR regenerated immutablePath");
			RouteAuthorizationLifecycle.invalidateAuthorization(simulator, current, ReleaseReason.INVALID, RequestState.INVALID);
			RouteOverride.clearOneShotOverride(simulator, current, "immutablePath changed");
		}
		RouteAuthorizationLifecycle.refreshActivityAuthorization(simulator, current);
		if (current.request != null && (current.request.getState() == RequestState.ACTIVE || current.request.getState() == RequestState.AUTHORIZED)
				&& current.authorization == null && current.activityAuthorization == null && current.tail < current.endDistance - 1.0E-6) {
			current.authorizationRetryPending = true;
			RouteAuthorizationLifecycle.enterAuthorizationRecovery(current);
		}

		if (current.authorization != null && current.authorizationEndDistance > head + 1.0E-6) {
			// Request 是完整进路申请，Authorization 是当前前缀。列车推进时由
			// finishSimulationTick 动态扩展 Authorization，无需在此处重建 Request。
			current.managed = true;
			return;
		}

		if (current.request != null
				&& current.request.getState() != RequestState.RELEASED
				&& current.request.getState() != RequestState.INVALID
				&& current.request.getState() != RequestState.REVOKED
				&& current.request.getState() != RequestState.CANCELED
				&& current.request.getPathFingerprint().equals(path.getFingerprint()) && !faceTopologyChanged) {
			current.managed = true;
			current.request.setRemainingPathDistance(Math.max(0, current.endDistance - head));
			return;
		}

		final ControlRange range = RouteAuthorizationPlanner.findControlRange(simulator, current);
		if (range == null) {
			final long now = System.currentTimeMillis();
			if (now - current.lastNoRangeDebugMillis >= 5000) {
				current.lastNoRangeDebugMillis = now;
				final List<PathSnapshot.FaceTraversal> allFaces = path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
				System.out.println("[MTRBR-NORANGE] vehicle=" + vehicle.getId()
						+ " head=" + String.format("%.1f", head)
						+ " pathTotal=" + String.format("%.1f", path.getTotalDistance())
						+ " faces=" + allFaces.size()
						+ " facesAhead=" + allFaces.stream().filter(face -> face.distance() > head).count());
			}
			final List<PathSnapshot.FaceTraversal> rawFaces = path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
			final boolean hasDirectionMatchedFaceAhead = rawFaces.stream()
					.filter(PathSnapshot::isDirectionMatched)
					.anyMatch(face -> face.distance() > head);
			// A never-managed route without a same-direction face remains unmanaged.
			// If one exists but the control range cannot be proven (fence/topology/
			// snapshot), retain fail-closed management and let the existing boundary hold.
			current.managed = hasDirectionMatchedFaceAhead || current.hasRetainedControl();
			return;
		}

		// The preview boundary is represented by the first ahead control face.
		// Once an immutable path has a same-direction face ahead, create the
		// complete Request immediately; the min(stop, four-faces) value only caps
		// the authorization look-ahead below. Waiting until triggerStart made the
		// first request appear only when the train was already at the red face.
		current.managed = true;

		final boolean needsNewRequest = current.request == null
				|| current.request.getState() == RequestState.RELEASED
				|| current.request.getState() == RequestState.INVALID
				|| current.request.getState() == RequestState.REVOKED
				|| current.request.getState() == RequestState.CANCELED
			|| !current.request.getPathFingerprint().equals(path.getFingerprint());

		if (needsNewRequest) {
			final RouteRequest previousRequest = current.request;
			if (current.authorization != null) RouteAuthorizationLifecycle.invalidateAuthorization(simulator, current, ReleaseReason.INVALID, RequestState.INVALID);
			current.generation++;
			current.controlFaceId = range.faceId();
			current.controlDistance = range.controlDistance();
			// Request covers the complete immutablePath. The look-ahead window limits
			// only Authorization and never truncates the Request, including sidings.
			final double requestEndDistance = range.requestEndDistance();
			current.endDistance = requestEndDistance;
			current.authorizationLookaheadEndDistance = Math.min(requestEndDistance, range.lookaheadEndDistance());
			// Request describes the complete immutable physical route. Authorization
			// remains a separately computed, safe prefix beginning at controlDistance.
			final List<PathSnapshot.PathTraversal> requestTraversals = path.getTraversalsBetween(0, requestEndDistance);
			final List<String> sectionIds = path.getSectionIds(requestTraversals);
			final List<String> signalFaceIds = path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
					.filter(PathSnapshot::isDirectionMatched)
					.filter(point -> point.distance() >= 0 && point.distance() <= requestEndDistance)
					.map(PathSnapshot.FaceTraversal::faceId)
					.toList();
			final RouteRequest nextRequest = new RouteRequest(vehicle.getId(), path.getFingerprint(), current.generation, SectionStateManager.getCurrentTick(),
				Math.max(0, current.endDistance - head), sectionIds, requestTraversals, signalFaceIds);
			RouteAuthorizationLifecycle.adoptRetainedResources(simulator, current, previousRequest, nextRequest);
			current.request = nextRequest;
			MtrbrDebugLog.event("MTRBR-REQUEST-CREATED", "vehicle=" + vehicle.getId()
					+ " request=" + current.request.getRequestId() + " pathFingerprint=" + path.getFingerprint()
					+ " controlDistance=" + RouteMath.fmt(current.controlDistance) + " endDistance=" + RouteMath.fmt(current.endDistance)
					+ " sections=" + sectionIds.size());
			current.request.transitionIfChanged(RequestState.APPROACHING, "Entered control approach");
			current.request.transitionIfChanged(RequestState.REQUESTED, "Complete route request created");
			current.request.transitionIfChanged(RequestState.CHECKING, "Section check scheduled");
		} else if (current.authorization == null) {
			current.request.setRemainingPathDistance(Math.max(0, current.endDistance - head));
		}
	}

	public static void finishSimulationTick(Simulator simulator) {
		final State state = STATES.computeIfAbsent(simulator, ignored -> new State());
		processPendingMappingRetries(simulator, state);
		processConfirmedMtrRemovals(simulator, state);
		recordMissingObservations(state);
		sweepStaleProtection(simulator, state);
		refreshVehicleRequests(simulator, state);
		arbitrateRequests(simulator, state);
		RouteDispatchSnapshots.publishAuthorizations(simulator, state);
	}

	private static void recordMissingObservations(State state) {
		for (final Map.Entry<Long, VehicleState> entry : state.vehicles.entrySet()) {
			if (entry.getValue().observed) {
				continue;
			}
			final VehicleState removed = entry.getValue();
			removed.missingTicks++;
			MtrbrDebugLog.event("MTRBR-VEHICLE-MISSING", "vehicle=" + entry.getKey()
					+ " missingTicks=" + removed.missingTicks + " grace=" + MISSING_TICK_GRACE
					+ " lastHead=" + RouteMath.fmt(removed.lastObservedHead)
					+ " lastTick=" + removed.lastObservedTick);
			if (removed.missingTicks >= MISSING_TICK_GRACE && !removed.missingUnconfirmedLogged) {
				removed.missingUnconfirmedLogged = true;
				MtrbrDebugLog.event("MTRBR-VEHICLE-MISSING-UNCONFIRMED", "vehicle=" + entry.getKey()
						+ " missingTicks=" + removed.missingTicks + " action=RETAIN_FAIL_CLOSED"
						+ " authorization=" + (removed.authorization == null ? "none" : removed.authorization.getAuthorizationId())
						+ " sections=" + removed.sections);
			}
			// No confirmed MTR removal: retain every resource and VehicleState.
		}
		state.vehicles.values().forEach(vehicle -> vehicle.observed = false);
	}

	private static void sweepStaleProtection(Simulator simulator, State state) {
		final Map<String, Set<String>> retainedSections = new HashMap<>();
		final Map<String, Set<String>> retainedBlocks = new HashMap<>();
		final Map<String, Set<String>> retainedJunctions = new HashMap<>();
		boolean unknownResourceOwner = false;
		for (final VehicleState vehicle : state.vehicles.values()) {
			RouteAuthorizationLifecycle.retainPendingOwners(vehicle, retainedSections, retainedBlocks, retainedJunctions);
			if (vehicle.request == null) {
				// Pending leases carry their own owners even without a Request.
				// Defer global cleanup only for protection whose identity is still unknown.
				unknownResourceOwner |= vehicle.authorization != null || vehicle.sections.stream()
						.anyMatch(id -> !vehicle.retainedProtection.protectsSection(id));
				continue;
			}
			final String owner = vehicle.request.getRequestId();
			vehicle.sections.forEach(id -> retainedSections.computeIfAbsent(id, ignored -> new HashSet<>()).add(owner));
			if (vehicle.authorization != null) {
				for (final Authorization.BlockAuthorization block : vehicle.authorization.getBlockAuthorizations()) {
					block.sectionIds().forEach(id -> retainedSections.computeIfAbsent(id, ignored -> new HashSet<>()).add(owner));
					List.of(block.occurrenceId(), block.blockId()).forEach(id -> retainedBlocks.computeIfAbsent(id, ignored -> new HashSet<>()).add(owner));
					block.junctionMovementIds().forEach(id -> retainedJunctions.computeIfAbsent(id, ignored -> new HashSet<>()).add(owner));
				}
			}
		}
		if (!unknownResourceOwner) {
			SectionStateManager.releaseStaleReservations(simulator, Set.of(), retainedSections, retainedBlocks);
			JunctionStateManager.releaseStale(simulator, Set.of(), retainedJunctions);
		} else {
			MtrbrDebugLog.event("MTRBR-STALE-CLEANUP-DEFERRED", "reason=UNKNOWN_LIVE_RESOURCE_OWNER action=FAIL_CLOSED");
		}
	}

	private static void refreshVehicleRequests(Simulator simulator, State state) {
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.missingTicks > 0) {
				continue;
			}
			// Pending interlocking leases are independent of dispatcher management.
			RouteAuthorizationLifecycle.releasePendingReleaseOccupancy(simulator, vehicle);
			if (!vehicle.managed) {
				continue;
			}
			if (vehicle.request == null) {
				continue;
			}
			// Retired requests finish physical cleanup even when their old path is stale.
			if (RouteAuthorizationLifecycle.isTerminal(vehicle.request.getState())) {
				if (vehicle.authorization != null) {
					RouteAuthorizationLifecycle.invalidateAuthorization(simulator, vehicle, ReleaseReason.INVALID, vehicle.request.getState());
				}
				RouteOverride.clearOneShotOverride(simulator, vehicle, "request terminal");
				if (vehicle.retainedProtection.isEmpty()) vehicle.request.transitionIfChanged(RequestState.RELEASED, "Pending resources cleared");
				continue;
			}
			if (!vehicle.path.matchesTopology(simulator)) {
				RouteAuthorizationLifecycle.invalidateAuthorization(simulator, vehicle, ReleaseReason.INVALID, RequestState.INVALID);
				RouteOverride.clearOneShotOverride(simulator, vehicle, "topology changed");
				continue;
			}
			RouteDiagnostics.auditDirection(simulator, vehicle);
			RouteTurnback.updateTurnbackHandoffTimeout(simulator, vehicle);
			if (vehicle.authorization != null) {
				RouteAuthorizationLifecycle.releaseAuthorizationPastHead(simulator, vehicle);
				if (vehicle.authorization != null) {
					RouteAuthorizationLifecycle.updateAuthorizedLifecycle(simulator, vehicle);
					RouteAuthorizationLifecycle.refreshAuthorizationLookahead(simulator, vehicle);
					RouteAuthorizationLifecycle.refreshActivityAuthorization(simulator, vehicle);
					if (vehicle.request != null && !RouteAuthorizationLifecycle.isTerminal(vehicle.request.getState())
							&& vehicle.authorizationEndDistance <= vehicle.head + 1.0E-6
							&& !RouteAuthorizationPlanner.hasContinuableSavedBlock(vehicle)) {
						vehicle.authorizationRetryPending = true;
						if (vehicle.request.getState() == RequestState.AUTHORIZED) {
							vehicle.request.transitionIfChanged(RequestState.ACTIVE, "Authorization prefix crossed; recovering from current head");
						}
						RouteDiagnostics.logAuthorizationExtendState(vehicle, "RETRY");
						MtrbrDebugLog.event("MTRBR-AUTH-RECOVERY", "vehicle=" + vehicle.vehicle.getId()
								+ " request=" + vehicle.request.getRequestId() + " reason=AUTHORIZATION_PREFIX_CROSSED"
								+ " headDistance=" + RouteMath.fmt(vehicle.head));
					}
					continue;
				}
			}

			final boolean continuingAuthorization = vehicle.request.getState() == RequestState.AUTHORIZED
					|| vehicle.request.getState() == RequestState.ACTIVE;
			if (continuingAuthorization) {
				final boolean mayRetry = vehicle.missingTicks == 0
						&& vehicle.tail < vehicle.endDistance - 1.0E-6
						&& vehicle.authorization == null
						&& vehicle.activityAuthorization == null;
				if (mayRetry) {
					// Keep the request identity and enqueue it for the FCFS pass below.
					// A released prefix is not a route completion: the next authorization
					// must begin from the current head, never from a historical control point.
					vehicle.authorizationRetryPending = true;
					MtrbrDebugLog.event("MTRBR-AUTH-RETRY", "vehicle=" + vehicle.vehicle.getId()
							+ " request=" + vehicle.request.getRequestId() + " headDistance=" + RouteMath.fmt(vehicle.head)
							+ " oldAuthorization=" + (vehicle.lastAuthorizationId.isBlank() ? "<none>" : vehicle.lastAuthorizationId)
							+ " newAuthorizationStart=" + RouteMath.fmt(vehicle.head)
							+ " reason=AUTHORIZATION_AND_ACTIVITY_MISSING_REQUEUE_FCFS");
					RouteAuthorizationLifecycle.enterAuthorizationRecovery(vehicle);
					RouteDiagnostics.logAuthorizationExtendState(vehicle, "RETRY");
				}
				// The final previous block may have been released this tick. Recompute
				// the forward window before putting this request back into FCFS.
				RouteAuthorizationLifecycle.refreshAuthorizationLookahead(simulator, vehicle);
			}
			final long stateRevision = SectionStateManager.getStateRevision(simulator);
			final long tick = SectionStateManager.getCurrentTick();
			if (vehicle.request.getState() == RequestState.DENIED && (vehicle.lastCheckedStateRevision != stateRevision || tick - vehicle.lastCheckedTick >= 20)) {
				vehicle.request.transitionIfChanged(RequestState.CHECKING, "Relevant SectionState changed");
			}
			if (continuingAuthorization) {
				// A released prefix is retried by the ordered FCFS pass below. Do not
				// downgrade a running request merely because its next block is busy.
				continue;
			}
			if (vehicle.request.getState() == RequestState.DENIED || vehicle.request.getState() == RequestState.WAITING && vehicle.lastCheckedStateRevision == stateRevision) {
				continue;
			}
			final Clearance clearance = RouteAuthorizationPlanner.clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.authorizationLookaheadEndDistance);
			vehicle.lastCheckedStateRevision = stateRevision;
			vehicle.lastCheckedTick = tick;
			final boolean activeWithoutAuthorization = vehicle.request.getState() == RequestState.ACTIVE;
			final RequestState checkResult = activeWithoutAuthorization ? RequestState.CHECKING
					: (clearance.sectionIds().isEmpty() ? RequestState.DENIED : RequestState.WAITING);
			vehicle.request.transitionIfChanged(checkResult, activeWithoutAuthorization ? "Authorization prefix expired; retrying extension"
							: (clearance.sectionIds().isEmpty() ? "First section unavailable" : "Waiting for FCFS"));
			if (clearance.sectionIds().isEmpty()) {
				RouteDiagnostics.auditAuthorizationFailure(simulator, vehicle, clearance);
			}
		}
	}

	private static void arbitrateRequests(Simulator simulator, State state) {
		final List<RouteRequest> waiting = new ArrayList<>();
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.request != null && (vehicle.request.getState() == RequestState.WAITING
					|| vehicle.request.getState() == RequestState.CHECKING && vehicle.authorizationRetryPending
					|| needsAuthorizationCompetition(vehicle))) {
				waiting.add(vehicle.request);
			}
		}
		waiting.stream()
				.sorted(Comparator.comparingLong(RouteRequest::getCreatedTick).thenComparingLong(RouteRequest::getVehicleId))
				.forEach(item -> MtrbrDebugLog.event("MTRBR-FCFS-ORDER", "vehicle=" + item.getVehicleId()
						+ " request=" + item.getRequestId() + " sequence=" + item.getCreatedTick()
						+ " priority=" + item.getManualPriority()));
		while (!waiting.isEmpty()) {
			final RouteRequest request = Dispatcher.selectFcfs(waiting).orElse(null);
			if (request == null) {
				break;
			}
			waiting.remove(request);
			final VehicleState vehicle = state.vehicles.get(request.getVehicleId());
			if (vehicle == null) {
				continue;
			}
			if (RouteMovementControl.isDepartureGuardBlocked(simulator, vehicle)) {
				continue;
			}
			if (vehicle.authorization != null) {
				RouteDiagnostics.logAuthorizationExtendState(vehicle, "EXTEND");
				RouteAuthorizationLifecycle.extendAuthorization(simulator, vehicle);
				continue;
			}

			// Request 覆盖完整进路；Authorization 只开放到第一个被占用/冲突 Section 之前，
			// 因此授权范围始终小于等于 Request 范围，不会因为前方占用而整条 DENIED。
			final boolean continuingAuthorization = request.getState() == RequestState.AUTHORIZED || request.getState() == RequestState.ACTIVE;
			final boolean retryingAuthorization = vehicle.authorization == null && vehicle.authorizationRetryPending;
			final double authorizationStart = retryingAuthorization
					? vehicle.head
					: continuingAuthorization
					? Math.max(vehicle.head, vehicle.authorizationEndDistance)
					: vehicle.controlDistance;
			final Clearance clearance = RouteAuthorizationPlanner.clearancePrefix(simulator, vehicle, authorizationStart, vehicle.authorizationLookaheadEndDistance);
			final List<String> authorizedSections = clearance.sectionIds();
			if (authorizedSections.isEmpty()) {
				RouteTurnback.logTurnbackReacquire(simulator, vehicle, RouteTurnback.turnbackReacquireFailure(simulator, vehicle));
				if (continuingAuthorization) {
					RouteDiagnostics.logAuthorizationExtendFail(vehicle, authorizationStart, vehicle.authorizationLookaheadEndDistance, "<none>", "no next block");
					RouteDiagnostics.logAuthorizationExtendState(vehicle, "WAIT");
				} else {
					RouteDiagnostics.auditAuthorizationFailure(simulator, vehicle, clearance);
				}
				continue;
			}
			if (!RouteAuthorizationLifecycle.reserveAndLock(simulator, vehicle, clearance)) {
				RouteTurnback.logTurnbackReacquire(simulator, vehicle, clearance.blockIds().isEmpty() ? "<none>" : clearance.blockIds().get(0), "FCFS_DENIED");
				if (continuingAuthorization) RouteDiagnostics.logAuthorizationExtendState(vehicle, "WAIT");
				continue;
			}
			final Authorization authorization = RouteAuthorizationLifecycle.createAuthorization(simulator, state, vehicle, clearance.blockAuthorizations());
			for (final String blockId : authorization.getBlockIds()) {
				final String audit = "vehicle=" + request.getVehicleId() + " blockId=" + blockId + " source=SAVED_DATA";
				MtrbrDebugLog.event("AUTH-BLOCK", audit);
			}
			vehicle.authorization = authorization;
			vehicle.clearFixedGate();
			RouteTurnback.logTurnbackReacquire(simulator, vehicle, authorization.getBlockIds().isEmpty() ? "<none>" : authorization.getBlockIds().get(0), "GRANTED");
			vehicle.lastAuthorizationId = authorization.getAuthorizationId();
			vehicle.authorizationRetryPending = false;
			vehicle.activityFallbackTicks = 0;
			RouteDiagnostics.logAuthorizationLifecycle(simulator, vehicle, "AUTHORIZATION_GRANTED");
			vehicle.authorizationEndDistance = clearance.endDistance();
			if (!continuingAuthorization) {
				request.transitionIfChanged(RequestState.AUTHORIZED, "FCFS progressive authorization");
			}
			RouteDiagnostics.logAuthorizationEnd(vehicle, "FCFS", clearance.endDistance());
		}
	}

	/** Called after SavedData receives mappings derived from observed immutable paths. */
	public static void notifyProtectionMappingsChanged(String dimension) {
		if (dimension != null && !dimension.isBlank()) PENDING_MAPPING_RETRIES.add(dimension);
	}

	private static void processPendingMappingRetries(Simulator simulator, State state) {
		if (!PENDING_MAPPING_RETRIES.remove(simulator.dimension)) return;
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.request == null || vehicle.path == null || vehicle.path.isEmpty()) continue;
			if (vehicle.request.getState() != RequestState.DENIED && vehicle.request.getState() != RequestState.WAITING
					&& vehicle.request.getState() != RequestState.CHECKING) continue;
			vehicle.lastCheckedStateRevision = -1;
			vehicle.lastCheckedTick = -20;
			vehicle.request.transitionIfChanged(RequestState.CHECKING, "Protection occurrence mappings changed");
			MtrbrDebugLog.event("MTRBR-BLOCK-DEFINITION-RETRY-QUEUED", "vehicle=" + vehicle.vehicle.getId()
					+ " request=" + vehicle.request.getRequestId() + " pathFingerprint=" + vehicle.path.getFingerprint()
					+ " action=RECHECK_FCFS");
		}
	}

	/** Enqueues a confirmed native MTR removal; resource cleanup is deferred to the simulator tick tail. */
	public static void onMtrVehicleRemoved(Vehicle vehicle, String reason) {
		if (vehicle == null) return;
		CONFIRMED_MTR_REMOVALS.putIfAbsent(vehicle.getId(), new ConfirmedVehicleRemoval(reason == null ? "MTR_REMOVED" : reason));
		MtrbrDebugLog.event("MTRBR-VEHICLE-REMOVAL-CONFIRMED", "vehicle=" + vehicle.getId() + " reason=" + reason + " action=QUEUED");
	}

	private static void processConfirmedMtrRemovals(Simulator simulator, State state) {
		for (final var iterator = CONFIRMED_MTR_REMOVALS.entrySet().iterator(); iterator.hasNext();) {
			final var entry = iterator.next();
			final long vehicleId = entry.getKey();
			final String reason = entry.getValue().reason();
			final VehicleState vehicle = state.vehicles.get(vehicleId);
			if (vehicle == null && STATES.values().stream().anyMatch(owner -> owner.vehicles.containsKey(vehicleId))) {
				// Siding edits may enqueue outside the owning simulator's tick. Keep the
				// event for that simulator instead of discarding its code/occupancy here.
				continue;
			}
			cleanupConfirmedRemoval(simulator, state, vehicle, vehicleId, reason, false);
			MtrbrDebugLog.event("MTRBR-VEHICLE-REMOVAL-CLEANUP", "vehicle=" + vehicleId + " reason=" + reason + " action=CLEANED");
			iterator.remove();
		}
	}

	/**
	 * Explicit operator recovery for a vehicle retained after disappearing without
	 * an MTR lifecycle confirmation. Normal MTR removal remains unchanged.
	 */
	public static boolean confirmQuarantinedVehicleRemoval(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null || vehicle.missingTicks < MISSING_TICK_GRACE) return false;
		return removeVehicle(simulator, state, vehicle, vehicleId, "OPERATOR_CONFIRMED_QUARANTINE");
	}

	/** Explicit operator removal for a vehicle intentionally quarantined from WebUI. */
	public static boolean forceRemoveVehicle(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null) return false;
		return removeVehicle(simulator, state, vehicle, vehicleId, "OPERATOR_CONFIRMED_MANUAL_REMOVAL");
	}

	private static boolean removeVehicle(Simulator simulator, State state, VehicleState vehicle, long vehicleId, String reason) {
		cleanupConfirmedRemoval(simulator, state, vehicle, vehicleId, reason, true);
		MtrbrDebugLog.event("MTRBR-VEHICLE-QUARANTINE-CLEANUP", "vehicle=" + vehicleId + " reason=" + reason + " action=CLEANED");
		return true;
	}

	/** Physical removal is confirmed; terminal business states must not be canceled again. */
	private static void cleanupConfirmedRemoval(Simulator simulator, State state, VehicleState vehicle,
			long vehicleId, String reason, boolean removeFromSidings) {
		if (vehicle != null) {
			if (vehicle.request != null && !RouteAuthorizationLifecycle.isTerminal(vehicle.request.getState())) {
				RouteAuthorizationLifecycle.invalidateAuthorization(simulator, vehicle, ReleaseReason.CANCELED, RequestState.CANCELED);
			}
			RouteAuthorizationLifecycle.releaseAll(simulator, vehicle);
			RouteOverride.clearOneShotOverride(simulator, vehicle, removeFromSidings ? reason : "MTR removal: " + reason);
			if (vehicle.vehicle != null) {
				PathSnapshot.clear(vehicle.vehicle);
				if (removeFromSidings) {
					simulator.sidings.forEach(siding -> ((SidingAccess) (Object) siding).mtrbr$getVehicles().remove(vehicle.vehicle));
				}
			}
			state.vehicles.remove(vehicleId);
		}
		SectionStateManager.removeVehicleOccupancy(vehicleId, reason);
		CapacityLeaseManager.releaseVehicle(vehicleId, reason);
		MovementGate.clear(vehicleId);
		RouteVehicleCodes.releaseVehicleCode(vehicleId);
	}

	private static boolean needsAuthorizationCompetition(VehicleState vehicle) {
		if (vehicle.request == null) return false;
		final RequestState requestState = vehicle.request.getState();
		if (requestState != RequestState.AUTHORIZED && requestState != RequestState.ACTIVE) return false;
		// The prefix can reach its head boundary while its old Blocks remain locked
		// for the vehicle tail. That is a continuation request, not a reason to
		// skip FCFS until the old Authorization disappears.
		return vehicle.authorizationRetryPending
				|| vehicle.authorization == null
				|| vehicle.authorizationEndDistance < vehicle.authorizationLookaheadEndDistance - 1.0E-6;
	}

	/**
	 * 渐进减速的红灯位置：未授权时取当前控制边界；已授权时取授权末端之后第一个信号。
	 * 所有车辆（含已授权车）都以此为目标点按 MTR 制动曲线渐进减速。无红点（授权覆盖到
	 * 进路终点）时返回 NaN（不限速）。
	 */
	public static double getStopBoundary(Simulator simulator, long vehicleId) {
		return RouteMovementControl.getStopBoundary(simulator, vehicleId);
	}

	public static boolean isFixedUnauthorisedGateBoundary(Simulator simulator, long vehicleId) {
		return RouteMovementControl.isFixedUnauthorisedGateBoundary(simulator, vehicleId);
	}

	public static double getLastSafeBoundary(Simulator simulator, long vehicleId) {
		return RouteMovementControl.getLastSafeBoundary(simulator, vehicleId);
	}

	/** Queues an OP-approved manual override on the simulator's own thread. */
	public static void setManualDrivingOverride(Simulator simulator, long vehicleId, boolean enabled) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null) {
				if (!enabled) RouteOverride.clearOneShotOverride(simulator, vehicle, "manual override disabled");
				MtrbrDebugLog.event("DISPATCH", "manual-driving-override vehicle=" + vehicleId + " enabled=false reason=ONE_SHOT_ONLY");
			}
		});
	}

	/**
	 * Sets an operator priority for a pending request. Authorization still flows
	 * through SectionCheck, reserve and lock; this only changes Dispatcher order.
	 */
	public static void setManualPriority(Simulator simulator, long vehicleId, int priority) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null && vehicle.request != null && vehicle.authorization == null) {
				vehicle.request.setManualPriority(priority);
				MtrbrDebugLog.event("DISPATCH", "priority vehicle=" + vehicleId + " request=" + vehicle.request.getRequestId() + " value=" + priority);
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-priority vehicle=" + vehicleId + " priority=" + priority);
			}
		});
	}

	/** 人工批准：直接按 SectionCheck 结果授权到 Request 内最后一个可开放 Section。 */
	public static void approveWaiting(Simulator simulator, long vehicleId) {
		approveWaiting(simulator, vehicleId, result -> {});
	}

	public record DispatchResult(boolean accepted, String reason, String detail) {}

	public static void approveWaiting(Simulator simulator, long vehicleId, java.util.function.Consumer<DispatchResult> reply) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			reply.accept(approveAuthorization(simulator, state, vehicle));
		});
	}

	/** Simulation-thread operation shared with behavior regression fixtures. */
	static DispatchResult approveAuthorization(Simulator simulator, State state, VehicleState vehicle) {
		if (vehicle == null || vehicle.request == null) return new DispatchResult(false, "request_missing", "");
		if (RouteAuthorizationLifecycle.isTerminal(vehicle.request.getState())) return new DispatchResult(false, "request_terminal", vehicle.request.getState().name());
		if (vehicle.authorization == null && vehicle.request.getState() != RequestState.WAITING
				&& !vehicle.request.canTransitionTo(RequestState.CHECKING)) return new DispatchResult(false, "request_state", vehicle.request.getState().name());
		if (vehicle.authorization != null && vehicle.request.getState() != RequestState.AUTHORIZED
				&& vehicle.request.getState() != RequestState.ACTIVE) return new DispatchResult(false, "request_state", vehicle.request.getState().name());
		if (vehicle.path == null || !vehicle.path.matchesTopology(simulator)) return new DispatchResult(false, "topology_changed", "");
		final double previousEnd = vehicle.authorization == null ? Double.NEGATIVE_INFINITY : vehicle.authorizationEndDistance;
		if (vehicle.authorization == null) {
			grantAuthorizationPrefix(simulator, state, vehicle, "Manual dispatcher approval");
		} else {
			RouteAuthorizationLifecycle.extendAuthorization(simulator, vehicle);
		}
		state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-approve vehicle=" + vehicle.vehicle.getId());
		final boolean advanced = vehicle.authorization != null && vehicle.authorizationEndDistance > previousEnd + 1.0E-6;
		return new DispatchResult(advanced, advanced ? "approved" : "no_clearance", vehicle.authorization == null ? vehicle.request.getReason() : "");
	}

	/** Human dispatcher revocation. Physical occupancy remains protected until the tail clears each Section. */
	public static void revokePendingAuthorization(Simulator simulator, long vehicleId) {
		revokePendingAuthorization(simulator, vehicleId, result -> {});
	}

	public static void revokePendingAuthorization(Simulator simulator, long vehicleId, java.util.function.Consumer<DispatchResult> reply) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			final DispatchResult result = RouteAuthorizationLifecycle.revokeAuthorization(simulator, vehicle);
			if (result.accepted()) {
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-revoke vehicle=" + vehicleId);
			} else {
				MtrbrDebugLog.event("DISPATCH", "revoke vehicle=" + vehicleId + " accepted=false reason=" + result.reason());
			}
			reply.accept(result);
		});
	}

	/** 一次性越过当前红灯：执行层放行一个信号边界后自动失效。 */
	public static void grantOneShotOverride(Simulator simulator, long vehicleId) {
		grantOneShotOverride(simulator, vehicleId, result -> {});
	}

	public static void grantOneShotOverride(Simulator simulator, long vehicleId, java.util.function.Consumer<DispatchResult> reply) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			final boolean eligible = RouteOverride.canGrantOneShotOverride(vehicle);
			final OverrideFace target = eligible ? RouteOverride.nextRedOverrideFace(simulator, vehicle) : null;
			if (eligible && target != null && Double.isFinite(target.distance())) {
				vehicle.overridePermit = new OverridePermit(OverrideState.ONE_SHOT, vehicleId, vehicle.request.getRequestId(), target.key(),
						vehicle.head, target.distance(), vehicle.path.getFingerprint(), SectionStateManager.getTopologyRevision(simulator), SectionStateManager.getCurrentTick());
				vehicle.overrideEndDistance = target.distance();
				vehicle.oneShotOverride = true;
				vehicle.overrideState = OverrideState.ONE_SHOT;
				MtrbrDebugLog.event("OVERRIDE", "vehicle=" + vehicleId + " request=" + vehicle.request.getRequestId() + " face=" + target.key() + " boundary=" + target.distance());
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-override vehicle=" + vehicleId + " until=" + vehicle.overrideEndDistance);
				reply.accept(new DispatchResult(true, "override", ""));
			} else {
				final String reason = vehicle == null || vehicle.request == null ? "REQUEST_NOT_READY"
						: RouteAuthorizationLifecycle.isTerminal(vehicle.request.getState()) ? "REQUEST_TERMINAL"
						: vehicle.path == null || vehicle.path.isEmpty() ? "PATH_EMPTY"
						: vehicle.authorization != null ? "ALREADY_AUTHORIZED"
						: target == null ? "NO_RED_FACE" : "INVALID_TARGET";
				MtrbrDebugLog.event("OVERRIDE", "vehicle=" + vehicleId + " accepted=false reason=" + reason);
				reply.accept(new DispatchResult(false, reason.toLowerCase(java.util.Locale.ROOT), ""));
			}
		});
	}

	public static boolean hasValidOneShotOverride(Simulator simulator, long vehicleId) {
		return RouteOverride.hasValidOneShotOverride(simulator, vehicleId);
	}

	public static double getOneShotOverrideBoundary(Simulator simulator, long vehicleId) {
		return RouteOverride.getOneShotOverrideBoundary(simulator, vehicleId);
	}

	public static boolean hasOneShotOverride(Simulator simulator, long vehicleId) {
		return RouteOverride.hasOneShotOverride(simulator, vehicleId);
	}

	/** True only while a valid route prefix is physically reserved and locked. */
	public static boolean hasAuthorization(Simulator simulator, long vehicleId) {
		return RouteMovementControl.hasAuthorization(simulator, vehicleId);
	}

	public static double getActivityEnd(Simulator simulator, long vehicleId) {
		return RouteMovementControl.getActivityEnd(simulator, vehicleId);
	}

	/** Boundary audit consumed by MovementGate; contains no path-derived signal chain. */
	public static GateBoundaryInfo getGateBoundaryInfo(Simulator simulator, long vehicleId) {
		return RouteMovementControl.getGateBoundaryInfo(simulator, vehicleId);
	}

	public record GateBoundaryInfo(double activityEnd, List<String> activityFaces, String nextSignalCandidate, String stopBoundarySource) {
		public GateBoundaryInfo { activityFaces = List.copyOf(activityFaces); }
	}

	private static void grantAuthorizationPrefix(Simulator simulator, State state, VehicleState vehicle, String reason) {
		vehicle.request.transitionIfChanged(RequestState.CHECKING, "Manual dispatcher clearance check");
		final Clearance clearance = RouteAuthorizationPlanner.clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.authorizationLookaheadEndDistance);
		if (clearance.sectionIds().isEmpty()) {
			RouteDiagnostics.auditAuthorizationFailure(simulator, vehicle, clearance);
			vehicle.request.transitionIfChanged(RequestState.DENIED, "No section can be authorized");
			return;
		}
		vehicle.request.transitionIfChanged(RequestState.WAITING, "Manual dispatcher clearance available");
		if (!RouteAuthorizationLifecycle.reserveAndLock(simulator, vehicle, clearance)) {
			return;
		}
		final Authorization authorization = RouteAuthorizationLifecycle.createAuthorization(simulator, state, vehicle, clearance.blockAuthorizations());
		vehicle.authorization = authorization;
		vehicle.clearFixedGate();
		vehicle.authorizationEndDistance = clearance.endDistance();
		vehicle.request.transitionIfChanged(RequestState.AUTHORIZED, reason);
		RouteDiagnostics.logAuthorizationEnd(vehicle, "MANUAL", clearance.endDistance());
	}

	public static boolean isManualDrivingOverride(Simulator simulator, long vehicleId) {
		return RouteMovementControl.isManualDrivingOverride(simulator, vehicleId);
	}

	/** Whether this vehicle's movement is currently owned by the addon MovementGate. */
	public static boolean isManaged(Simulator simulator, long vehicleId) {
		return RouteMovementControl.isManaged(simulator, vehicleId);
	}

	/** At the turnback stop, let MTR perform its native terminate/door/reverse cycle. */
	public static boolean isTurnbackHandoff(Simulator simulator, long vehicleId) {
		return RouteTurnback.isTurnbackHandoff(simulator, vehicleId);
	}

	/** True only after native MTR reversal and before the same-tick observer rebuild. */
	public static boolean isNativeTurnbackActivityTransition(Simulator simulator, long vehicleId) {
		return RouteTurnback.isNativeTurnbackActivityTransition(simulator, vehicleId);
	}

	public static void logNativeTurnbackActivityGateDeferral(Simulator simulator, long vehicleId) {
		RouteTurnback.logNativeTurnbackActivityGateDeferral(simulator, vehicleId);
	}

	/**
	 * The native terminating flag is set by MTR's own block/stop calculation, so
	 * it cannot be the only condition for handing a terminal platform back to
	 * MTR. Keep the handoff tightly scoped to the final four blocks before a
	 * planned reverse boundary.
	 */
	public static boolean isApproachingPlannedTurnback(Simulator simulator, long vehicleId) {
		return RouteTurnback.isApproachingPlannedTurnback(simulator, vehicleId);
	}

	public static List<AuthorizedPath> getAuthorizedPaths(Simulator simulator) {
		return RouteDispatchSnapshots.getAuthorizedPaths(simulator);
	}

	/** Vehicle position snapshots used only for SignalFace -> Section ID mapping; not an occupancy source. */
	public static List<VehicleSnapshot> getVehicleSnapshots(Simulator simulator) {
		return RouteDispatchSnapshots.getVehicleSnapshots(simulator);
	}

	/** Explicit protection regeneration only: compile observed directed protection boundaries into canonical data. */
	public static Map<String, GeneratedProtection> getGeneratedProtectionBlocks(Simulator simulator, ServerAspectManager.FaceSnapshot topology) {
		return RouteDispatchSnapshots.getGeneratedProtectionBlocks(simulator, topology);
	}

	/**
	 * Compiles each observed FaceTraversal occurrence independently. The key includes
	 * the immutable path fingerprint, so a repeated physical SignalFace cannot borrow
	 * another occurrence's boundary.
	 */
	public static Map<String, GeneratedProtection> getGeneratedOccurrenceProtectionBlocks(Simulator simulator, ServerAspectManager.FaceSnapshot topology) {
		return RouteDispatchSnapshots.getGeneratedOccurrenceProtectionBlocks(simulator, topology);
	}

	public record GeneratedProtection(String blockId, List<String> railIds, String boundaryId) {
		public GeneratedProtection { railIds = List.copyOf(railIds); }
	}

	public static List<RequestSnapshot> getRequestSnapshots(Simulator simulator) {
		return RouteDispatchSnapshots.getRequestSnapshots(simulator);
	}

	public static List<String> getAudit(Simulator simulator) {
		return RouteDispatchSnapshots.getAudit(simulator);
	}

	public static String getVehicleCode(long vehicleId) {
		return RouteVehicleCodes.getVehicleCode(vehicleId);
	}

	public static Long resolveVehicleCode(String code) {
		return RouteVehicleCodes.resolveVehicleCode(code);
	}

	/** Clears all request/authorization state when the server stops. */
	public static void resetAll() {
		// Server shutdown is not a business cancellation.  Request/authorization
		// state is runtime-owned and must not be transitioned while the server is
		// tearing down; in particular INVALID -> CANCELED is illegal.  MTR will
		// re-observe vehicles and paths on the next server start.
		STATES.clear();
		RouteDispatchSnapshots.reset();
		JunctionStateManager.resetAll();
		PathSnapshot.clearAll();
		MovementGate.clearAll();
		RouteVehicleCodes.reset();
		CONFIRMED_MTR_REMOVALS.clear();
		PENDING_MAPPING_RETRIES.clear();
	}

	public record AuthorizedPath(long vehicleId, String vehicleCode, PathSnapshot path, List<PathSnapshot.PathTraversal> traversals, List<PathSnapshot.FaceTraversalKey> historicalFaceTraversalKeys, double startDistance, double endDistance, List<String> activeBlockIds, List<PathSnapshot.FaceTraversalKey> activeFaceTraversalKeys, String authorizationId, long revision) {
	}

	public record ActivityAuthorization(double startDistance, double endDistance, List<String> blockIds, List<PathSnapshot.FaceTraversalKey> faceTraversalKeys, boolean valid) {
		public ActivityAuthorization { blockIds = List.copyOf(blockIds); faceTraversalKeys = List.copyOf(faceTraversalKeys); }
	}

	public record VehicleSnapshot(long vehicleId, String vehicleCode, PathSnapshot path, double head, double tail) {
	}

	public record RequestSnapshot(long vehicleId, String vehicleCode, RequestState state, double head, double controlDistance, double endDistance, double authorizationEndDistance, boolean authorized, boolean oneShotOverride, double speedKmh, String routeName, String destination, String nextStation, int occupiedBlocks, int authorizedBlocks, int lockedBlocks) {
	}

	private static boolean isAuthorizedFace(Authorization authorization, PathSnapshot.FaceTraversal face) {
		return authorization != null && authorization.getFaceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key()));
	}

	private record ConfirmedVehicleRemoval(String reason) {
	}

	static boolean completeTurnbackIfReady(Simulator simulator, VehicleState vehicle) {
		final TraversalContext context = vehicle.traversalContext;
		if (context == null) return false;
		final boolean terminating = vehicle.vehicle.vehicleExtraData.getIsTerminating();
		if (terminating && !context.turnbackBegun()) {
			vehicle.traversalContext = context.withTurnbackBegun(terminating);
			RouteTurnback.beginTurnbackHandoff(vehicle, "NATIVE_TERMINATION_STARTED", "DEFER_GATE_WRITES", "MTR_IS_TERMINATING");
			MtrbrDebugLog.event("MTR_TURNBACK_BEGIN", "vehicle=" + vehicle.vehicle.getId() + " request=" + (vehicle.request == null ? "-" : vehicle.request.getRequestId())
					+ " currentPathIndex=" + RouteMath.currentTraversalIndex(vehicle.path, vehicle.head)
					+ " terminating=" + vehicle.vehicle.vehicleExtraData.getIsTerminating() + " stopIndex=" + vehicle.vehicle.vehicleExtraData.getStopIndex());
			return false;
		}
		final boolean reversedChanged = vehicle.vehicle.getReversed() != context.reversed();
		final TurnbackIntent intent = vehicle.turnbackIntent;
		final boolean intentReversed = intent != null && intent.matches(vehicle.path) && !intent.reversalConfirmed()
				&& vehicle.vehicle.getReversed() != intent.initialReversed();
		if ((!context.turnbackBegun() && !intentReversed) || terminating || !reversedChanged && !intentReversed) return false;
		if (intentReversed) {
			vehicle.turnbackIntent = intent.withReversalConfirmed();
			MtrbrDebugLog.event("MTRBR-TURNBACK-INTENT", "vehicle=" + vehicle.vehicle.getId()
					+ " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
					+ " phase=REVERSED action=REVERSED_FALLBACK_CONFIRM reason=NATIVE_TERMINATING_TICK_NOT_OBSERVED");
		}
		return finalizeTurnback(simulator, vehicle, context, intentReversed ? "PERSISTED_INTENT_REVERSED" : "MTR_REVERSED_AFTER_NATIVE_TERMINATION");
	}

	static boolean finalizeTurnback(Simulator simulator, VehicleState vehicle, TraversalContext context, String reason) {
		final String oldActivity = RouteDiagnostics.describeActivity(vehicle.activityAuthorization);
		RouteAuthorizationLifecycle.releaseTurnbackWindow(simulator, vehicle);
		vehicle.turnbackHandoff.reversalObserved(SectionStateManager.getCurrentTick());
		vehicle.traversalContext = new TraversalContext(vehicle.path.getFingerprint(), vehicle.vehicle.getReversed(),
				vehicle.vehicle.vehicleExtraData.getStopIndex(), false, false);
		vehicle.controlDistance = Math.max(0, vehicle.head - 1.0E-6);
		vehicle.authorizationEndDistance = vehicle.head;
		// The previous direction's look-ahead is no longer valid after MTR has
		// completed the reversal. Rebuild the new-direction window before this
		// request re-enters FCFS; leaving it at head creates a zero-length prefix
		// and is misreported as a denied first Section.
		RouteAuthorizationLifecycle.refreshAuthorizationLookahead(simulator, vehicle);
		if (vehicle.request != null && vehicle.request.getState() == RequestState.ACTIVE) {
			vehicle.request.transitionIfChanged(RequestState.CHECKING, "MTR completed turnback; checking next traversal window");
		}
		MtrbrDebugLog.event("MTR_TURNBACK_COMPLETE", "vehicle=" + vehicle.vehicle.getId() + " request=" + (vehicle.request == null ? "-" : vehicle.request.getRequestId())
				+ " currentPathIndex=" + RouteMath.currentTraversalIndex(vehicle.path, vehicle.head) + " authorizationEnd=" + String.format("%.3f", vehicle.head));
		RouteTurnback.logTurnbackGateHandoff(vehicle, "RESUME_GATE", "REVERSED");
		MtrbrDebugLog.event("MTRBR-TURNBACK-PHASE", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
				+ " phase=REVERSED currentOccurrence=" + vehicle.path.getFingerprint() + "@"
				+ RouteMath.currentTraversalIndex(vehicle.path, vehicle.head) + ":reversed=true direction=REVERSED"
				+ " fenceDistance=<pending-reacquire> excludedFaces=[] source=MTR_TURNBACK_COMPLETE");
		RouteTurnback.logTurnbackActivity(vehicle, oldActivity, RouteDiagnostics.describeActivity(vehicle.activityAuthorization), "REVERSAL_OBSERVED",
				"REBUILD_NEW_DIRECTION_ACTIVITY", reason);
		return true;
	}
}
