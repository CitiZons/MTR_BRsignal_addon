package org.mtrbr.server;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtrbr.mixin.SidingAccess;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Simulation-thread owner of RouteRequest, FCFS selection and authorization lifecycles. */
public final class RouteRequestManager {
	private static final int MISSING_TICK_GRACE = 3;
	/** A simulation tick can advance the head slightly beyond a Block entry face. */
	private static final double BLOCK_ENTRY_FACE_TOLERANCE = 0.05;
	private static final Map<Simulator, State> STATES = new IdentityHashMap<>();
	private static final Map<Long, String> VEHICLE_CODES = new HashMap<>();
	private static final Map<String, Long> CODE_TO_VEHICLE = new HashMap<>();
	private static long nextVehicleCode;
	private static final String VEHICLE_CODE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".replace("O", "");
	/** Published only after a complete simulation tick; safe for the Forge server thread to read. */
	private static volatile Map<Simulator, List<AuthorizedPath>> AUTHORIZATION_SNAPSHOTS = Map.of();
	/** Vehicle position snapshots used only to map SignalFace -> Section IDs for authoritative occupancy reads. */
	private static volatile Map<Simulator, List<VehicleSnapshot>> VEHICLE_SNAPSHOTS = Map.of();
	private static volatile Map<Simulator, List<RequestSnapshot>> REQUEST_SNAPSHOTS = Map.of();
	private static volatile Map<Simulator, List<String>> AUDIT_SNAPSHOTS = Map.of();

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
		final boolean hadPreviousObservation = current.observed;
		current.vehicle = vehicle;
		current.path = path;
		current.head = head;
		current.tail = tail;
		current.sections = Set.copyOf(occupiedSections);
		current.observed = !path.isEmpty();
		current.missingTicks = 0;
		current.lastObservedHead = head;
		current.lastObservedTail = tail;
		current.lastObservedPath = path;
		current.lastObservedTick = SectionStateManager.getCurrentTick();
		if (path.isEmpty()) {
			return;
		}
		ensureTraversalContext(current);

		final List<PathSnapshot.FaceTraversal> faceTraversals = path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		for (final PathSnapshot.FaceTraversal faceTraversal : faceTraversals) {
			if (faceTraversal.distance() > current.lastHead && faceTraversal.distance() <= head) {
				current.lastPassedSignalMillis = System.currentTimeMillis();
			}
		}
		current.lastHead = head;
		if (current.oneShotOverride && head >= current.overrideEndDistance + 0.5) {
			clearOneShotOverride(simulator, current, "head passed override boundary");
		}

		if (!path.matchesTopology(simulator)) {
			logActivityInvalidation(simulator, current, "Path topology changed before vehicle movement");
			invalidateAuthorization(simulator, current, ReleaseReason.INVALID, RequestState.INVALID);
			clearOneShotOverride(simulator, current, "topology changed");
			current.managed = true;
			return;
		}
		if (completeTurnbackIfReady(simulator, current)) {
			refreshActivityAuthorization(simulator, current);
		}

		// MTR can restart a repeated depot route at distance zero after the train
		// completes a cycle/turnback while retaining the same immutable path
		// fingerprint. Do not carry the previous cycle's locked resources into the
		// new run; release them and let the normal request path create a new generation.
		if (hadPreviousObservation && current.request != null && current.authorization != null
				&& current.request.getPathFingerprint().equals(path.getFingerprint())
				&& head + 20.0 < previousHead) {
			final String requestId = current.request.getRequestId();
			MtrbrDebugLog.event("REQUEST-CYCLE-RESET", "vehicle=" + vehicle.getId()
					+ " request=" + requestId + " previousHead=" + String.format("%.1f", previousHead)
					+ " newHead=" + String.format("%.1f", head));
			releaseAll(simulator, current);
			transition(current.request, RequestState.RELEASED, "MTR repeated route cycle restarted");
			current.request = null;
			current.activityAuthorization = null;
			current.authorizationEndDistance = Double.NaN;
			current.controlFaceId = "";
			current.controlDistance = 0;
			current.endDistance = 0;
			current.authorizationLookaheadEndDistance = 0;
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
			logActivityInvalidation(simulator, current, "MTR regenerated immutablePath");
			invalidateAuthorization(simulator, current, ReleaseReason.INVALID, RequestState.INVALID);
			clearOneShotOverride(simulator, current, "immutablePath changed");
		}
		refreshActivityAuthorization(simulator, current);
		if (current.request != null && (current.request.getState() == RequestState.ACTIVE || current.request.getState() == RequestState.AUTHORIZED)
				&& current.authorization == null && current.activityAuthorization == null && current.tail < current.endDistance - 1.0E-6) {
			current.authorizationRetryPending = true;
			enterAuthorizationRecovery(current);
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
				&& current.request.getPathFingerprint().equals(path.getFingerprint())) {
			current.managed = true;
			current.request.setRemainingPathDistance(Math.max(0, current.endDistance - head));
			return;
		}

		final ControlRange range = findControlRange(simulator, path, head);
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
			current.managed = false;
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
			release(simulator, current);
			current.generation++;
			current.controlFaceId = range.faceId();
			current.controlDistance = range.controlDistance();
			// Request covers the complete immutablePath. The look-ahead window limits
			// only Authorization and never truncates the Request, including sidings.
			final double requestEndDistance = range.requestEndDistance();
			if (requestEndDistance <= current.controlDistance) {
				current.managed = false;
				return;
			}
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
			current.request = new RouteRequest(vehicle.getId(), path.getFingerprint(), current.generation, SectionStateManager.getCurrentTick(),
				Math.max(0, current.endDistance - head), sectionIds, requestTraversals, signalFaceIds);
			transition(current.request, RequestState.APPROACHING, "Entered control approach");
			transition(current.request, RequestState.REQUESTED, "Complete route request created");
			transition(current.request, RequestState.CHECKING, "Section check scheduled");
		} else if (current.authorization == null) {
			current.request.setRemainingPathDistance(Math.max(0, current.endDistance - head));
		}
	}

	public static void finishSimulationTick(Simulator simulator) {
		final State state = STATES.computeIfAbsent(simulator, ignored -> new State());
	state.vehicles.entrySet().removeIf(entry -> {
			if (entry.getValue().observed) {
				return false;
			}
			final VehicleState removed = entry.getValue();
			removed.missingTicks++;
			MtrbrDebugLog.event("MTRBR-VEHICLE-MISSING", "vehicle=" + entry.getKey()
					+ " missingTicks=" + removed.missingTicks + " grace=" + MISSING_TICK_GRACE
					+ " lastHead=" + fmt(removed.lastObservedHead)
					+ " lastTick=" + removed.lastObservedTick);
			if (removed.missingTicks < MISSING_TICK_GRACE) {
				return false;
			}
			if (removed.request != null && removed.request.getState() != RequestState.RELEASED) {
				if (removed.request.getState() != RequestState.CANCELED && removed.request.getState() != RequestState.REVOKED && removed.request.getState() != RequestState.INVALID) {
					transition(removed.request, RequestState.CANCELED, "Vehicle disappeared");
				}
				invalidateAuthorization(simulator, removed, ReleaseReason.VEHICLE_REMOVED, RequestState.CANCELED);
				if (removed.request.getState() != RequestState.RELEASED) transition(removed.request, RequestState.RELEASED, "Vehicle disappeared; resources released");
			}
			releaseAll(simulator, removed);
			clearOneShotOverride(simulator, removed, "vehicle disappeared");
			releaseVehicleCode(entry.getKey());
			return true;
		});
		state.vehicles.values().forEach(vehicle -> vehicle.observed = false);
		final Set<String> activeAuthorizationRequests = state.vehicles.values().stream()
				.filter(vehicle -> vehicle.missingTicks == 0 && vehicle.request != null
						&& vehicle.request.getState() != RequestState.RELEASED
						&& vehicle.request.getState() != RequestState.CANCELED
						&& vehicle.request.getState() != RequestState.INVALID
						&& vehicle.request.getState() != RequestState.REVOKED
						&& vehicle.request.getState() != RequestState.PASSED)
				.map(vehicle -> vehicle.request.getRequestId()).collect(java.util.stream.Collectors.toSet());
		SectionStateManager.releaseStaleReservations(simulator, activeAuthorizationRequests);
		JunctionStateManager.releaseStale(simulator, activeAuthorizationRequests);

		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.missingTicks > 0) {
				continue;
			}
			if (!vehicle.managed) {
				continue;
			}
			if (vehicle.request == null) {
				releasePendingReleaseOccupancy(simulator, vehicle);
				continue;
			}
			if (!vehicle.path.matchesTopology(simulator)) {
				invalidateAuthorization(simulator, vehicle, ReleaseReason.INVALID, RequestState.INVALID);
				clearOneShotOverride(simulator, vehicle, "topology changed");
				continue;
			}
			auditDirection(simulator, vehicle);
			if (vehicle.request.getState() == RequestState.REVOKED || vehicle.request.getState() == RequestState.CANCELED || vehicle.request.getState() == RequestState.INVALID) {
				releasePendingReleaseOccupancy(simulator, vehicle);
				if (vehicle.pendingReleaseSections.isEmpty() && vehicle.pendingReleaseBlocks.isEmpty() && vehicle.request.getState() != RequestState.RELEASED) {
					transition(vehicle.request, RequestState.RELEASED, "Pending resources cleared");
				}
				continue;
			}
			releasePendingReleaseOccupancy(simulator, vehicle);
			if (vehicle.authorization != null) {
				releaseAuthorizationPastHead(simulator, vehicle);
				if (vehicle.authorization != null) {
					updateAuthorizedLifecycle(simulator, vehicle);
					refreshAuthorizationLookahead(simulator, vehicle);
					refreshActivityAuthorization(simulator, vehicle);
					if (vehicle.request != null && !isTerminal(vehicle.request.getState())
							&& vehicle.authorizationEndDistance <= vehicle.head + 1.0E-6) {
						vehicle.authorizationRetryPending = true;
						if (vehicle.request.getState() == RequestState.AUTHORIZED) {
							transition(vehicle.request, RequestState.ACTIVE, "Authorization prefix crossed; recovering from current head");
						}
						logAuthorizationExtendState(vehicle, "RETRY");
						MtrbrDebugLog.event("MTRBR-AUTH-RECOVERY", "vehicle=" + vehicle.vehicle.getId()
								+ " request=" + vehicle.request.getRequestId() + " reason=AUTHORIZATION_PREFIX_CROSSED"
								+ " headDistance=" + fmt(vehicle.head));
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
							+ " request=" + vehicle.request.getRequestId() + " headDistance=" + fmt(vehicle.head)
							+ " oldAuthorization=" + (vehicle.lastAuthorizationId.isBlank() ? "<none>" : vehicle.lastAuthorizationId)
							+ " newAuthorizationStart=" + fmt(vehicle.head)
							+ " reason=AUTHORIZATION_AND_ACTIVITY_MISSING_REQUEUE_FCFS");
					enterAuthorizationRecovery(vehicle);
					logAuthorizationExtendState(vehicle, "RETRY");
				}
				// The final previous block may have been released this tick. Recompute
				// the forward window before putting this request back into FCFS.
				refreshAuthorizationLookahead(simulator, vehicle);
			}
			final long stateRevision = SectionStateManager.getStateRevision(simulator);
			final long tick = SectionStateManager.getCurrentTick();
			if (vehicle.request.getState() == RequestState.DENIED && (vehicle.lastCheckedStateRevision != stateRevision || tick - vehicle.lastCheckedTick >= 20)) {
				transition(vehicle.request, RequestState.CHECKING, "Relevant SectionState changed");
			}
			if (continuingAuthorization) {
				// A released prefix is retried by the ordered FCFS pass below. Do not
				// downgrade a running request merely because its next block is busy.
				continue;
			}
			if (vehicle.request.getState() == RequestState.DENIED || vehicle.request.getState() == RequestState.WAITING && vehicle.lastCheckedStateRevision == stateRevision) {
				continue;
			}
			final Clearance clearance = clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.authorizationLookaheadEndDistance);
			vehicle.lastCheckedStateRevision = stateRevision;
			vehicle.lastCheckedTick = tick;
			final boolean activeWithoutAuthorization = vehicle.request.getState() == RequestState.ACTIVE;
			final RequestState checkResult = activeWithoutAuthorization ? RequestState.CHECKING
					: (clearance.sectionIds().isEmpty() ? RequestState.DENIED : RequestState.WAITING);
			transition(vehicle.request, checkResult,
					activeWithoutAuthorization ? "Authorization prefix expired; retrying extension"
							: (clearance.sectionIds().isEmpty() ? "First section unavailable" : "Waiting for FCFS"));
			if (clearance.sectionIds().isEmpty()) {
				auditAuthorizationFailure(simulator, vehicle, clearance);
			}
		}

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
			if (isDepartureGuardBlocked(simulator, vehicle)) {
				continue;
			}
			if (vehicle.authorization != null) {
				logAuthorizationExtendState(vehicle, "EXTEND");
				extendAuthorization(simulator, vehicle);
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
			final Clearance clearance = clearancePrefix(simulator, vehicle, authorizationStart, vehicle.authorizationLookaheadEndDistance);
			final List<String> authorizedSections = clearance.sectionIds();
			if (authorizedSections.isEmpty()) {
				if (continuingAuthorization) {
					logAuthorizationExtendFail(vehicle, authorizationStart, vehicle.authorizationLookaheadEndDistance, "<none>", "no next block");
					logAuthorizationExtendState(vehicle, "WAIT");
				} else {
					auditAuthorizationFailure(simulator, vehicle, clearance);
				}
				continue;
			}
			if (!reserveAndLock(simulator, vehicle, clearance)) {
				if (continuingAuthorization) logAuthorizationExtendState(vehicle, "WAIT");
				continue;
			}
			final Authorization authorization = createAuthorization(simulator, state, vehicle, clearance.blockAuthorizations(), false);
			for (final String blockId : authorization.getBlockIds()) {
				final String audit = "vehicle=" + request.getVehicleId() + " blockId=" + blockId + " source=SAVED_DATA";
				MtrbrDebugLog.event("AUTH-BLOCK", audit);
				System.out.println("[MTRBR-AUTH-BLOCK] " + audit);
			}
			vehicle.authorization = authorization;
			vehicle.lastAuthorizationId = authorization.getAuthorizationId();
			vehicle.authorizationRetryPending = false;
			vehicle.activityFallbackTicks = 0;
			logAuthorizationLifecycle(simulator, vehicle, "AUTHORIZATION_GRANTED");
			vehicle.authorizationEndDistance = clearance.endDistance();
			if (!continuingAuthorization) {
				transition(request, RequestState.AUTHORIZED, "FCFS progressive authorization");
			}
			logAuthorizationEnd(vehicle, "FCFS", clearance.endDistance());
		}
		debugVehicles(simulator, state);
		publishAuthorizations(simulator, state);
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

	private static ControlRange findControlRange(Simulator simulator, PathSnapshot path, double head) {
		final List<ControlPoint> ahead = path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched)
				.map(point -> new ControlPoint(point))
				.filter(point -> point.traversal().distance() > head)
				.toList();
		if (ahead.isEmpty()) {
			return null;
		}
		final double controlDistance = ahead.get(0).traversal().distance();
		final PathSnapshot.TurnbackWindow turnback = path.getNextTurnbackWindow(controlDistance);
		final double stopBoundary = turnback.stopDistance();
		final double turnbackBoundary = turnback.requiresTurnback() ? turnback.endDistance() : Double.POSITIVE_INFINITY;
		final double fiveSignalBoundary = ahead.size() > 4 ? ahead.get(4).traversal().distance() : path.getTotalDistance();
		final double lookaheadEndDistance = Math.min(stopBoundary, Math.min(turnbackBoundary, fiveSignalBoundary));
		// A stopping point exactly at the first control face still needs a
		// Request. Keep a zero-length authorization window so SectionCheck marks
		// it denied and MovementGate holds the train at that face.
		final double effectiveLookaheadEnd = Math.max(controlDistance, lookaheadEndDistance);
		final List<String> signalFaceIds = ahead.stream()
				.filter(point -> point.traversal().distance() >= controlDistance && point.traversal().distance() <= effectiveLookaheadEnd)
				.map(point -> faceTraversalKey(point.traversal()))
				.toList();
		return new ControlRange(ahead.get(0).traversal().faceId(), controlDistance, effectiveLookaheadEnd, path.getTotalDistance(), 0, signalFaceIds);
	}

	private static void ensureTraversalContext(VehicleState vehicle) {
		if (vehicle.traversalContext == null || !vehicle.traversalContext.pathFingerprint().equals(vehicle.path.getFingerprint())) {
			vehicle.traversalContext = new TraversalContext(vehicle.path.getFingerprint(), vehicle.vehicle.getReversed(),
					vehicle.vehicle.vehicleExtraData.getStopIndex(), vehicle.vehicle.vehicleExtraData.getIsTerminating(), false);
		}
	}

	private static int currentTraversalIndex(PathSnapshot path, double distance) {
		for (final PathSnapshot.PathTraversal traversal : path.getTraversals()) {
			if (distance < traversal.endDistance() - 1.0E-6) return traversal.index();
		}
		return Math.max(0, path.getTraversals().size() - 1);
	}

	/** Switches windows only after MTR has actually reversed the vehicle. */
	private static boolean completeTurnbackIfReady(Simulator simulator, VehicleState vehicle) {
		final TraversalContext context = vehicle.traversalContext;
		if (context == null) return false;
		final boolean terminating = vehicle.vehicle.vehicleExtraData.getIsTerminating();
		if (terminating && !context.turnbackBegun()) {
			vehicle.traversalContext = context.withTurnbackBegun(terminating);
			MtrbrDebugLog.event("MTR_TURNBACK_BEGIN", "vehicle=" + vehicle.vehicle.getId() + " request=" + (vehicle.request == null ? "-" : vehicle.request.getRequestId())
					+ " currentPathIndex=" + currentTraversalIndex(vehicle.path, vehicle.head)
					+ " terminating=" + vehicle.vehicle.vehicleExtraData.getIsTerminating() + " stopIndex=" + vehicle.vehicle.vehicleExtraData.getStopIndex());
			return false;
		}
		final boolean reversedChanged = vehicle.vehicle.getReversed() != context.reversed();
		if (!context.turnbackBegun() || terminating || !reversedChanged) return false;
		releaseTurnbackWindow(simulator, vehicle);
		vehicle.traversalContext = new TraversalContext(vehicle.path.getFingerprint(), vehicle.vehicle.getReversed(),
				vehicle.vehicle.vehicleExtraData.getStopIndex(), false, false);
		vehicle.controlDistance = Math.max(0, vehicle.head - 1.0E-6);
		vehicle.authorizationLookaheadEndDistance = vehicle.head;
		vehicle.authorizationEndDistance = vehicle.head;
		if (vehicle.request != null && vehicle.request.getState() == RequestState.ACTIVE) {
			transition(vehicle.request, RequestState.CHECKING, "MTR completed turnback; checking next traversal window");
		}
		MtrbrDebugLog.event("MTR_TURNBACK_COMPLETE", "vehicle=" + vehicle.vehicle.getId() + " request=" + (vehicle.request == null ? "-" : vehicle.request.getRequestId())
				+ " currentPathIndex=" + currentTraversalIndex(vehicle.path, vehicle.head) + " authorizationEnd=" + String.format("%.3f", vehicle.head));
		return true;
	}

	private static void releaseTurnbackWindow(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) return;
		final String requestId = vehicle.request.getRequestId();
		final Set<String> pendingSections = new HashSet<>();
		for (final Authorization.BlockAuthorization block : vehicle.authorization.getBlockAuthorizations()) {
			if (vehicle.tail < block.endDistance() - 1.0E-6) pendingSections.addAll(block.sectionIds());
		}
		pendingSections.addAll(vehicle.sections);
		final Set<String> immediateSections = new HashSet<>(vehicle.authorization.getSectionIds());
		immediateSections.removeAll(pendingSections);
		SectionStateManager.releaseSections(simulator, immediateSections, requestId);
		for (final String sectionId : pendingSections) {
			vehicle.pendingReleaseSections.put(sectionId, ReleaseReason.TURNBACK);
		}
		final List<PathSnapshot.PathTraversal> clearedTraversals = new ArrayList<>();
		for (final Authorization.BlockAuthorization block : vehicle.authorization.getBlockAuthorizations()) {
			if (vehicle.tail < block.endDistance() - 1.0E-6) {
				registerPendingRelease(simulator, vehicle, block, ReleaseReason.TURNBACK);
			} else {
				SectionStateManager.releaseBlocks(simulator, blockLockIds(List.of(block)), requestId);
				clearedTraversals.addAll(block.traversals());
			}
		}
		JunctionStateManager.release(simulator, JunctionStateManager.resourcesFor(simulator, clearedTraversals), requestId);
		vehicle.authorization = null;
		vehicle.activityAuthorization = null;
		vehicle.authorizationRetryPending = true;
	}

	/** 沿 Request 区段逐 Rail 检查；Section 的唯一单位就是一条无向 Rail。 */
	private static Clearance clearancePrefix(Simulator simulator, VehicleState vehicle, double startDistance, double endDistance) {
		endDistance = Math.min(endDistance, authorizationBoundary(simulator, vehicle, startDistance));
		if (endDistance < startDistance) {
			endDistance = startDistance;
		}
		final List<String> authorizedRailIds = new ArrayList<>();
		final List<String> authorizedBlockIds = new ArrayList<>();
		final List<PathSnapshot.PathTraversal> authorizedTraversals = new ArrayList<>();
		final List<PathSnapshot.FaceTraversalKey> authorizedFaces = new ArrayList<>();
		final List<Authorization.BlockAuthorization> authorizedBlocks = new ArrayList<>();
		double authorizedEnd = startDistance;
		final List<PathSnapshot.FaceTraversal> faces = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched).toList();
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final PathSnapshot.FaceTraversal face : faces) {
			// Continue from the current head, but still accept the entry face of the
			// Block the head has just crossed in this simulation tick. Without this
			// tolerance, a head at 228.921 skips an entry face at 228.913 while the
			// next face equals the window end, yielding a false "no next block".
			if (face.distance() < startDistance - BLOCK_ENTRY_FACE_TOLERANCE || face.distance() >= endDistance) continue;
			final SavedBlockTraversal block = savedBlockTraversal(vehicle.path, faces, savedBlocks, face);
			if (block == null) {
				logAuthorizationExtendFail(vehicle, startDistance, endDistance, "<missing>", "block missing");
				final String audit = "face=" + face.faceId() + " blockId=<missing> nextFace=<unknown> railCount=0";
				MtrbrDebugLog.event("BLOCK-ID", audit + " source=SAVED_DATA");
				System.out.println("[MTRBR-BLOCK] " + audit);
				break;
			}
			// Saved Blocks may straddle an operational boundary. Project the part up
			// to the hard authorization limit instead of either borrowing its far
			// side or refusing the entire prefix.
			final double candidateEnd = Math.min(block.endDistance(), endDistance);
			final boolean completeSavedBlock = block.endDistance() <= endDistance + 1.0E-6;
			final List<PathSnapshot.PathTraversal> candidateTraversals = vehicle.path.getTraversalsBetween(face.distance(), candidateEnd).stream()
					.filter(traversal -> block.railIds().contains(traversal.sectionId())).toList();
			final List<PathSnapshot.FaceTraversalKey> projectedFaceKeys = candidateFaceKeys(vehicle.path, faces, candidateTraversals, face.distance(), candidateEnd);
			final List<PathSnapshot.FaceTraversalKey> boundaryFaceKeys = faces.stream()
					.filter(item -> candidateTraversals.stream().anyMatch(traversal -> traversal.index() == item.pathTraversalIndex()))
					.filter(item -> item.distance() >= face.distance() - 1.0E-6 && item.distance() <= candidateEnd + 1.0E-6)
					.map(PathSnapshot.FaceTraversal::key).toList();
			if (projectedFaceKeys.isEmpty()) {
				// The entry Face is the authoritative identity for this saved Block.
				// A traversal-index projection gap is diagnostic, but must not deny
				// the first safe Block and strand a vehicle before departure.
				logAuthorizationExtendFail(vehicle, startDistance, endDistance, block.blockId(), "key mismatch (entry-key fallback)");
			}
			final List<PathSnapshot.FaceTraversalKey> effectiveFaceKeys = combineFaceTraversalKeys(
					projectedFaceKeys.isEmpty() ? List.of(face.key()) : projectedFaceKeys, boundaryFaceKeys);
			final Authorization.BlockAuthorization candidate = new Authorization.BlockAuthorization(block.blockId(),
					candidateTraversals.stream().mapToInt(PathSnapshot.PathTraversal::index).min().orElse(-1),
					candidateTraversals.stream().mapToDouble(PathSnapshot.PathTraversal::startDistance).min().orElse(face.distance()),
					candidateEnd, vehicle.path.getSectionIds(candidateTraversals), candidateTraversals, effectiveFaceKeys, completeSavedBlock);
			auditCandidateBlock(simulator, vehicle, candidate, savedBlocks);
			if (!SectionStateManager.areBlocksAvailable(simulator, blockLockIds(List.of(candidate)), vehicle.request.getRequestId())) {
				logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "block conflict");
				MtrbrDebugLog.event("MTRBR-CONFLICT", "type=BLOCK vehicle=" + vehicle.request.getVehicleId()
						+ " blockedBy=<block-state> resource=" + blockLockIds(List.of(candidate))
						+ " range=" + fmt(candidate.startDistance()) + ".." + fmt(candidate.endDistance()));
				break;
			}
			final List<String> junctionResources = JunctionStateManager.resourcesFor(simulator, candidate.traversals());
			if (JunctionStateManager.conflicts(simulator, junctionResources, vehicle.request.getRequestId())) {
				logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "junction conflict");
				MtrbrDebugLog.event("MTRBR-CONFLICT", "type=JUNCTION_MOVEMENT vehicle=" + vehicle.request.getVehicleId()
						+ " blockedBy=<junction-state> resource=" + junctionResources
						+ " range=" + fmt(candidate.startDistance()) + ".." + fmt(candidate.endDistance()));
				break;
			}
			final String blockAudit = "face=" + face.faceId() + " blockId=" + block.blockId() + " traversalIndex=" + face.pathTraversalIndex() + " nextBoundary=" + block.boundaryId() + " railCount=" + block.railIds().size();
			MtrbrDebugLog.event("BLOCK-ID", blockAudit + " rails=" + block.railIds() + " source=SAVED_DATA");
			System.out.println("[MTRBR-BLOCK] " + blockAudit);
			final SectionCheck.BlockResult check = SectionCheck.checkBlock(simulator, block.blockId(), candidate.sectionIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false);
			if (!check.safe()) {
				logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "section conflict");
				final SectionCheck.SectionResult result = check.sections().sections().stream().filter(item -> item.status() != SectionCheck.Status.AVAILABLE).findFirst().orElse(null);
				MtrbrDebugLog.event("MTRBR-FCFS-CONFLICT", "type=SECTION vehicle=" + vehicle.request.getVehicleId()
						+ " blockedBy=" + (result == null || result.state() == null ? "<unknown>" : result.state().occupiedBy)
						+ " resource=" + (result == null ? block.blockId() : result.sectionId())
						+ " range=" + fmt(candidate.startDistance()) + ".." + fmt(candidate.endDistance()));
				MtrbrDebugLog.event("CHECK", "vehicle=" + vehicle.request.getVehicleId()
						+ " request=" + vehicle.request.getRequestId()
						+ " block=" + block.blockId() + " rails=" + block.railIds()
						+ " status=" + check.status()
						+ " rail=" + (result == null ? "-" : result.sectionId()));
				break;
			}
			final List<PathSnapshot.PathTraversal> blockTraversals = new ArrayList<>(candidateTraversals);
			if (blockTraversals.isEmpty()) {
				logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "no next block");
				break;
			}
			authorizedBlockIds.add(block.blockId());
			for (final String railId : candidate.sectionIds()) {
				if (!authorizedRailIds.contains(railId)) authorizedRailIds.add(railId);
			}
			for (final PathSnapshot.PathTraversal traversal : blockTraversals) {
				if (authorizedTraversals.stream().noneMatch(item -> item.index() == traversal.index())) authorizedTraversals.add(traversal);
			}
			final double blockStart = blockTraversals.stream().mapToDouble(PathSnapshot.PathTraversal::startDistance).min().orElse(face.distance());
			final double blockEnd = candidate.endDistance();
			final List<PathSnapshot.FaceTraversalKey> blockFaces = effectiveFaceKeys;
			authorizedFaces.addAll(blockFaces);
			final int occurrence = blockTraversals.get(0).index();
			authorizedBlocks.add(new Authorization.BlockAuthorization(block.blockId(), occurrence, blockStart, blockEnd, candidate.sectionIds(), blockTraversals, blockFaces, completeSavedBlock));
			authorizedEnd = Math.max(authorizedEnd, blockEnd);
		}
		return new Clearance(List.copyOf(authorizedRailIds), List.copyOf(authorizedBlockIds), List.copyOf(authorizedTraversals), List.copyOf(authorizedFaces), List.copyOf(authorizedBlocks), authorizedEnd);
	}

	/** The hard upper bound applied at every authorization construction site. */
	private static double authorizationBoundary(Simulator simulator, VehicleState vehicle, double startDistance) {
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(Math.max(vehicle.head, startDistance - 1.0E-6));
		final double stopBoundary = turnback.stopDistance();
		final double turnbackBoundary = turnback.requiresTurnback() ? turnback.endDistance() : Double.POSITIVE_INFINITY;
		final double fiveSignalBoundary = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched)
				.filter(face -> face.distance() > Math.max(vehicle.head, startDistance) + 1.0E-6)
				.skip(4)
				.mapToDouble(PathSnapshot.FaceTraversal::distance)
				.findFirst().orElse(vehicle.path.getTotalDistance());
		return Math.min(stopBoundary, Math.min(turnbackBoundary, fiveSignalBoundary));
	}

	/** Acquires a checked prefix as one transaction; Authorization changes only after this succeeds. */
	private static boolean reserveAndLock(Simulator simulator, VehicleState vehicle, Clearance clearance) {
		final String requestId = vehicle.request.getRequestId();
		JunctionStateManager.registerOwner(simulator, requestId, vehicle.request.getVehicleId());
		final List<String> blockLockIds = blockLockIds(clearance.blockAuthorizations());
		final List<String> junctionResources = JunctionStateManager.resourcesFor(simulator, clearance.traversals());
		if (!validateAuthorizationResources(simulator, vehicle, clearance, blockLockIds, junctionResources)) {
			return false;
		}
		boolean blocksReserved = false;
		boolean sectionsReserved = false;
		boolean completed = false;
		try {
			if (!SectionStateManager.reserveBlocks(simulator, blockLockIds, requestId)) return false;
			blocksReserved = true;
			if (!SectionStateManager.reserveSections(simulator, clearance.sectionIds(), requestId, vehicle.request.getVehicleId(), false)) return false;
			sectionsReserved = true;
			if (!SectionStateManager.lockSections(simulator, clearance.sectionIds(), requestId)) return false;
			if (!SectionStateManager.lockBlocks(simulator, blockLockIds, requestId)) return false;
			if (!JunctionStateManager.reserve(simulator, junctionResources, requestId)) return false;
			if (!JunctionStateManager.lock(simulator, junctionResources, requestId)) return false;
			completed = true;
			return true;
		} finally {
			// A failed reservation/lock must never leave a partial route behind.
			if (!completed) {
				JunctionStateManager.release(simulator, junctionResources, requestId);
				if (sectionsReserved) SectionStateManager.releaseSections(simulator, clearance.sectionIds(), requestId);
				if (blocksReserved) SectionStateManager.releaseBlocks(simulator, blockLockIds, requestId);
			}
		}
	}

	/** Stage B: read-only resource validation. No reservation or lock is mutated here. */
	private static boolean validateAuthorizationResources(Simulator simulator, VehicleState vehicle, Clearance clearance,
			List<String> blockLockIds, List<String> junctionResources) {
		final String requestId = vehicle.request.getRequestId();
		if (!SectionStateManager.areBlocksAvailable(simulator, blockLockIds, requestId)) {
			MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=BLOCK vehicle=" + vehicle.request.getVehicleId()
					+ " blockedBy=<block-state> resource=" + blockLockIds
					+ " range=" + fmt(vehicle.controlDistance) + ".." + fmt(clearance.endDistance()));
			return false;
		}
		if (!SectionStateManager.areSectionsAvailable(simulator, clearance.sectionIds(), requestId, vehicle.request.getVehicleId(), false)) {
			MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=SECTION vehicle=" + vehicle.request.getVehicleId()
					+ " blockedBy=<section-state> resource=" + clearance.sectionIds()
					+ " range=" + fmt(vehicle.controlDistance) + ".." + fmt(clearance.endDistance()));
			return false;
		}
		final List<String> junctionOwners = JunctionStateManager.conflictOwners(simulator, junctionResources, requestId);
		if (!junctionOwners.isEmpty()) {
			MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=JUNCTION_MOVEMENT vehicle=" + vehicle.request.getVehicleId()
					+ " blockedBy=" + junctionOwners + " resource=" + junctionResources
					+ " range=" + fmt(vehicle.controlDistance) + ".." + fmt(clearance.endDistance()));
			MtrbrDebugLog.event("MTRBR-DEADLOCK", "vehicle=" + vehicle.request.getVehicleId()
					+ " waitingResource=" + junctionResources + " ownerVehicle=" + junctionOwners
					+ " heldResources=<tracked-by-owner>");
			return false;
		}
		return true;
	}

	private static Authorization createAuthorization(Simulator simulator, State state, VehicleState vehicle,
			List<Authorization.BlockAuthorization> blocks, boolean manualDrivingOverride) {
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final Authorization.BlockAuthorization block : blocks) {
			auditBlockProjection(simulator, vehicle, block, savedBlocks);
		}
		final double start = blocks.stream().mapToDouble(Authorization.BlockAuthorization::startDistance).min().orElse(vehicle.head);
		final double end = blocks.stream().mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(start);
		return new Authorization(vehicle.request.getRequestId() + ":auth", vehicle.request.getRequestId(), blocks,
				vehicle.path.getPathNodesBetween(start, end), SectionStateManager.getTopologyRevision(simulator),
				state == null ? vehicle.authorization.getRevision() + 1 : ++state.authorizationRevision, manualDrivingOverride);
	}

	/** Locks keep the canonical Block ID for physical mutual exclusion and the
	 * occurrence ID for lifecycle/audit identity. */
	private static List<String> blockLockIds(List<Authorization.BlockAuthorization> blocks) {
		final java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
		for (final Authorization.BlockAuthorization block : blocks) {
			if (block.completeSavedBlock()) {
				ids.add(block.blockId());
			}
			ids.add(block.occurrenceId());
		}
		return List.copyOf(ids);
	}

	/** 在已授权前缀之后继续尝试锁闭下一段空闲 Section，使 Authorization 随列车推进动态扩展。 */
	private static void extendAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) {
			return;
		}
		refreshAuthorizationLookahead(simulator, vehicle);
		if (vehicle.authorizationEndDistance >= vehicle.authorizationLookaheadEndDistance - 1.0E-6) {
			logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, vehicle.authorizationLookaheadEndDistance, "<none>", "already extended");
			logAuthorizationExtendState(vehicle, "WAIT");
			return;
		}
		final double extensionStart = Math.max(vehicle.head, vehicle.authorizationEndDistance);
		final Clearance extension = firstBlock(clearancePrefix(simulator, vehicle, extensionStart, vehicle.authorizationLookaheadEndDistance));
		if (extension.sectionIds().isEmpty() || extension.traversals().isEmpty()) {
			logAuthorizationExtendFail(vehicle, extensionStart, vehicle.authorizationLookaheadEndDistance, "<none>", "no next block");
			logAuthorizationExtendState(vehicle, "WAIT");
			return;
		}

		final State state = STATES.get(simulator);
		if (!reserveAndLock(simulator, vehicle, extension)) {
			logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, vehicle.authorizationLookaheadEndDistance,
					extension.blockIds().isEmpty() ? "<unknown>" : extension.blockIds().get(0), "fcfs denied");
			logAuthorizationExtendState(vehicle, "WAIT");
			return;
		}

		final Set<String> combinedSections = new java.util.LinkedHashSet<>(vehicle.authorization.getSectionIds());
		combinedSections.addAll(extension.sectionIds());
		final Set<String> combinedBlocks = new java.util.LinkedHashSet<>(vehicle.authorization.getBlockIds());
		combinedBlocks.addAll(extension.blockIds());
		final List<PathSnapshot.PathTraversal> combinedTraversals = new ArrayList<>(vehicle.authorization.getTraversals());
		for (final PathSnapshot.PathTraversal traversal : extension.traversals()) {
			if (combinedTraversals.stream().noneMatch(existing -> existing.index() == traversal.index())) {
				combinedTraversals.add(traversal);
			}
		}
		final List<Authorization.BlockAuthorization> combinedBlockAuthorizations = new ArrayList<>(vehicle.authorization.getBlockAuthorizations());
		combinedBlockAuthorizations.addAll(extension.blockAuthorizations());
		final Authorization extended = createAuthorization(simulator, state, vehicle, combinedBlockAuthorizations, false);
			for (final String blockId : extension.blockIds()) {
				final String audit = "vehicle=" + vehicle.request.getVehicleId() + " blockId=" + blockId + " source=SAVED_DATA";
				MtrbrDebugLog.event("AUTH-BLOCK", audit);
				System.out.println("[MTRBR-AUTH-BLOCK] " + audit);
			}
		vehicle.authorization = extended;
		vehicle.lastAuthorizationId = extended.getAuthorizationId();
		vehicle.authorizationEndDistance = extension.endDistance();
		auditAuthorizationOverextension(simulator, vehicle, vehicle.path.getNextTurnbackWindow(vehicle.head), vehicle.authorizationLookaheadEndDistance);
		logAuthorizationEnd(vehicle, "EXTEND", extension.endDistance());
	}

	private static void logAuthorizationExtendState(VehicleState vehicle, String action) {
		if (vehicle.request == null) return;
		MtrbrDebugLog.event("MTRBR-AUTH-EXTEND-STATE", "vehicle=" + vehicle.vehicle.getId()
				+ " requestState=" + vehicle.request.getState()
				+ " authorizationEnd=" + fmt(vehicle.authorizationEndDistance)
				+ " headDistance=" + fmt(vehicle.head)
				+ " action=" + action);
	}

	private static void logAuthorizationExtendFail(VehicleState vehicle, double currentEnd, double lookaheadEnd, String candidateBlock, String reason) {
		MtrbrDebugLog.event("MTRBR-AUTH-EXTEND-FAIL", "vehicle=" + vehicle.vehicle.getId()
				+ " currentEnd=" + fmt(currentEnd) + " lookaheadEnd=" + fmt(lookaheadEnd)
				+ " candidateBlock=" + candidateBlock + " reason=" + reason);
	}

	/** Extension is always one complete block, never a partial prefix of one. */
	private static Clearance firstBlock(Clearance clearance) {
		if (clearance.blockAuthorizations().isEmpty()) {
			return clearance;
		}
		final Authorization.BlockAuthorization block = clearance.blockAuthorizations().get(0);
		return new Clearance(block.sectionIds(), List.of(block.blockId()), block.traversals(), block.faceTraversalKeys(), List.of(block), block.endDistance());
	}

	/** Recompute the moving authorization preview from the current vehicle position. */
	private static void refreshAuthorizationLookahead(Simulator simulator, VehicleState vehicle) {
		trimAuthorizationPastOperationalBoundary(simulator, vehicle);
		final List<PathSnapshot.FaceTraversal> ahead = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched)
				.filter(face -> face.distance() > vehicle.head + 1.0E-6)
				.toList();
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(vehicle.head);
		final double stopBoundary = turnback.stopDistance();
		final double turnbackBoundary = turnback.requiresTurnback() ? turnback.endDistance() : Double.POSITIVE_INFINITY;
		final double fiveSignalBoundary = ahead.size() > 4 ? ahead.get(4).distance() : vehicle.path.getTotalDistance();
		final double recomputedEnd = Math.min(stopBoundary, Math.min(turnbackBoundary, fiveSignalBoundary));
		vehicle.authorizationLookaheadEndDistance = Math.min(vehicle.endDistance, Math.max(vehicle.head, recomputedEnd));
		MtrbrDebugLog.event("MTRBR-AUTH-WINDOW", "vehicle=" + vehicle.vehicle.getId()
				+ " headDistance=" + fmt(vehicle.head) + " stopBoundary=" + fmt(stopBoundary)
				+ " turnbackBoundary=" + fmt(turnbackBoundary) + " signalBoundary=" + fmt(fiveSignalBoundary)
				+ " finalAuthorizationEnd=" + fmt(vehicle.authorizationLookaheadEndDistance));
		auditAuthorizationOverextension(simulator, vehicle, turnback, vehicle.authorizationLookaheadEndDistance);
		logAuthorizationLookahead(vehicle, turnback, vehicle.authorizationLookaheadEndDistance);
	}

	/**
	 * Once the vehicle head has reached a scheduled platform boundary, retain the
	 * inbound Block containing that boundary but release any pre-authorized
	 * departure Blocks beyond it. This is a resource-boundary correction only;
	 * it does not add a vehicle state or alter signal Aspect calculation.
	 */
	private static void trimAuthorizationPastOperationalBoundary(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) return;
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(vehicle.head - 1.0E-6);
		if (turnback.requiresTurnback() || vehicle.head < turnback.stopDistance() - 1.0E-6) return;
		final double boundary = turnback.stopDistance();
		final List<Authorization.BlockAuthorization> active = vehicle.authorization.getBlockAuthorizations();
		final List<Authorization.BlockAuthorization> retained = active.stream()
				.filter(block -> block.startDistance() < boundary - 1.0E-6)
				.toList();
		if (retained.size() == active.size()) return;
		final List<Authorization.BlockAuthorization> removed = active.stream()
				.filter(block -> block.startDistance() >= boundary - 1.0E-6)
				.toList();
		final List<Authorization.BlockAuthorization> cleared = removed.stream()
				.filter(block -> vehicle.tail >= block.endDistance() - 1.0E-6)
				.toList();
		final List<Authorization.BlockAuthorization> pending = removed.stream()
				.filter(block -> vehicle.tail < block.endDistance() - 1.0E-6)
				.toList();
		final String requestId = vehicle.request.getRequestId();
		SectionStateManager.releaseBlocks(simulator, blockLockIds(cleared), requestId);
		for (final Authorization.BlockAuthorization block : pending) {
			registerPendingRelease(simulator, vehicle, block, ReleaseReason.INVALID);
		}
		final Set<String> retainedSections = new HashSet<>();
		for (final Authorization.BlockAuthorization block : retained) retainedSections.addAll(block.sectionIds());
		for (final Authorization.BlockAuthorization block : pending) retainedSections.addAll(block.sectionIds());
		retainedSections.addAll(vehicle.sections);
		final Set<String> removedSections = new HashSet<>();
		for (final Authorization.BlockAuthorization block : removed) removedSections.addAll(block.sectionIds());
		removedSections.removeAll(retainedSections);
		SectionStateManager.releaseSections(simulator, removedSections, requestId);
		final List<PathSnapshot.PathTraversal> removedTraversals = cleared.stream()
				.flatMap(block -> block.traversals().stream()).toList();
		JunctionStateManager.release(simulator, JunctionStateManager.resourcesFor(simulator, removedTraversals), requestId);
		vehicle.authorization = retained.isEmpty() ? null : createAuthorization(simulator, STATES.get(simulator), vehicle, retained, false);
		if (vehicle.authorization == null) {
			vehicle.authorizationRetryPending = true;
			enterAuthorizationRecovery(vehicle);
		}
		vehicle.authorizationEndDistance = retained.stream()
				.mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(vehicle.head);
		MtrbrDebugLog.event("AUTH-BOUNDARY-TRIM", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + requestId + " operationalBoundary=" + fmt(boundary)
				+ " releasedBlocks=" + cleared.stream().map(Authorization.BlockAuthorization::blockId).toList()
				+ " pendingBlocks=" + pending.stream().map(Authorization.BlockAuthorization::blockId).toList()
				+ " authorizationEnd=" + fmt(vehicle.authorizationEndDistance));
	}

	private static void auditAuthorizationOverextension(Simulator simulator, VehicleState vehicle,
			PathSnapshot.TurnbackWindow turnback, double lookaheadEnd) {
		if (vehicle.authorization == null || vehicle.authorization.getBlockAuthorizations().isEmpty()) return;
		final double operationalBoundary = turnback.requiresTurnback() ? turnback.endDistance() : turnback.stopDistance();
		if (vehicle.authorizationEndDistance <= operationalBoundary + 1.0E-6) return;
		final Authorization.BlockAuthorization departure = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> block.startDistance() >= operationalBoundary - 1.0E-6).findFirst().orElse(null);
		if (departure == null) return;
		final String departureFaceKey = departure.faceTraversalKeys().stream().map(Object::toString).findFirst().orElse("<none>");
		MtrbrDebugLog.event("MTRBR-AUTH-OVEREXTEND", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + vehicle.request.getRequestId() + " headDistance=" + fmt(vehicle.head)
				+ " operationalBoundary=" + fmt(operationalBoundary)
				+ " authorizationEnd=" + fmt(vehicle.authorizationEndDistance)
				+ " lookaheadEnd=" + fmt(lookaheadEnd) + " departureBlock=" + departure.blockId()
				+ " departureFaceKey=" + departureFaceKey + " reason=AUTHORIZATION_PASSES_OPERATIONAL_BOUNDARY");
	}

	private static void logAuthorizationLookahead(VehicleState vehicle, PathSnapshot.TurnbackWindow turnback, double lookaheadEnd) {
		final int currentStopIndex = vehicle.vehicle.vehicleExtraData.getStopIndex();
		final String signature = currentStopIndex + "|" + turnback.stopIndex() + "|"
				+ String.format(java.util.Locale.ROOT, "%.3f|%.3f", vehicle.authorizationEndDistance, lookaheadEnd);
		if (signature.equals(vehicle.lastAuthorizationLookaheadSignature)) return;
		vehicle.lastAuthorizationLookaheadSignature = signature;
		final String audit = "vehicle=" + vehicle.vehicle.getId()
				+ " headDistance=" + String.format(java.util.Locale.ROOT, "%.3f", vehicle.head)
				+ " currentStopIndex=" + currentStopIndex
				+ " selectedStopIndex=" + turnback.stopIndex()
				+ " currentAuthorizationEnd=" + String.format(java.util.Locale.ROOT, "%.3f", vehicle.authorizationEndDistance)
				+ " newLookaheadEnd=" + String.format(java.util.Locale.ROOT, "%.3f", lookaheadEnd);
		MtrbrDebugLog.event("MTRBR-AUTH-LOOKAHEAD", audit);
		System.out.println("[MTRBR-AUTH-LOOKAHEAD] " + audit);
	}

	private static double nextBlockEnd(Simulator simulator, VehicleState vehicle, double afterDistance) {
		final List<PathSnapshot.FaceTraversal> faces = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched).toList();
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final PathSnapshot.FaceTraversal face : faces) {
			if (face.distance() + 1.0E-6 < afterDistance) continue;
			final SavedBlockTraversal block = savedBlockTraversal(vehicle.path, faces, savedBlocks, face);
			if (block != null && block.endDistance() > afterDistance + 1.0E-6) return block.endDistance();
		}
		return afterDistance;
	}

	private static void logAuthorizationEnd(VehicleState vehicle, String source, double endDistance) {
		if (vehicle.request == null) return;
		final Authorization authorization = vehicle.authorization;
		final double startDistance = authorization == null ? vehicle.head : authorization.getBlockAuthorizations().stream()
				.mapToDouble(Authorization.BlockAuthorization::startDistance).min().orElse(vehicle.head);
		final String blockId = authorization == null ? "-" : authorization.getBlockAuthorizations().stream()
				.map(Authorization.BlockAuthorization::blockId).reduce((first, second) -> second).orElse("-");
		final String faceTraversalKey = authorization == null ? "-" : authorization.getFaceTraversalKeys().stream()
				.map(Object::toString).reduce((first, second) -> second).orElse("-");
		MtrbrDebugLog.event("MTRBR-AUTH-END", "vehicle=" + vehicle.request.getVehicleId() + " request=" + vehicle.request.getRequestId()
				+ " source=" + source + " pathIndex=" + currentTraversalIndex(vehicle.path, vehicle.head)
				+ " authorizationStart=" + String.format("%.3f", startDistance)
				+ " authorizationEnd=" + String.format("%.3f", endDistance)
				+ " blockId=" + blockId + " faceTraversalKey=" + faceTraversalKey);
	}

	private static void logAuthorizationLifecycle(Simulator simulator, VehicleState vehicle, String reason) {
		if (vehicle.vehicle == null) return;
		final String requestId = vehicle.request == null ? "<none>" : vehicle.request.getRequestId();
		final String state = vehicle.request == null ? RequestState.NONE.name() : vehicle.request.getState().name();
		final String auth = vehicle.authorization == null ? "<none>" : vehicle.authorization.getAuthorizationId();
		final String activity = vehicle.activityAuthorization == null ? "<none>"
				: (vehicle.activityAuthorization.valid() ? fmt(vehicle.activityAuthorization.endDistance()) : "invalid");
		final double boundary = vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()
				? vehicle.activityAuthorization.endDistance() : Math.max(vehicle.head, finiteOr(vehicle.lastSafeBoundary, vehicle.head));
		MtrbrDebugLog.event("MTRBR-AUTH-LIFECYCLE", "request=" + requestId + " state=" + state
				+ " authorization=" + auth + " activity=" + activity + " head=" + fmt(vehicle.head)
				+ " tail=" + fmt(vehicle.tail) + " boundary=" + fmt(boundary) + " reason=" + reason);
	}

	/** Projects the cumulative Authorization onto the current operational window. */
	private static void refreshActivityAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.path == null) {
			if (vehicle.request != null && !isTerminal(vehicle.request.getState()) && vehicle.path != null
					&& vehicle.lastValidActivity != null && canReuseLastActivity(simulator, vehicle)) {
				vehicle.activityAuthorization = vehicle.lastValidActivity;
				vehicle.activityFallbackTicks++;
				MtrbrDebugLog.event("MTRBR-AUTH-RECOVERY", "vehicle=" + vehicle.vehicle.getId()
						+ " request=" + vehicle.request.getRequestId() + " reason=AUTHORIZATION_REFRESH_PENDING"
						+ " boundary=" + fmt(Math.max(vehicle.head, vehicle.lastValidActivity.endDistance())));
			} else if (vehicle.request == null || isTerminal(vehicle.request.getState()) || vehicle.path == null) {
				vehicle.activityAuthorization = null;
				vehicle.lastValidActivity = null;
			}
			logAuthorizationLifecycle(simulator, vehicle, "ACTIVITY_REFRESH_MISSING_AUTH");
			return;
		}
		final List<Authorization.BlockAuthorization> active = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> vehicle.tail < block.endDistance() - 1.0E-6)
				.toList();
		final double activeStart = vehicle.head;
		final double activeEnd = active.stream().mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(activeStart);
		final List<String> activeBlocks = active.stream().map(Authorization.BlockAuthorization::blockId).toList();
		// Face keys remain a display projection of the active directed Blocks.
		final List<PathSnapshot.FaceTraversalKey> activeFaceKeys = active.stream()
				.flatMap(block -> block.faceTraversalKeys().stream())
				.filter(key -> vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
						.anyMatch(face -> face.key().sameIdentity(key)))
				.distinct().toList();
		for (final Authorization.BlockAuthorization block : active) {
			auditBlockProjection(simulator, vehicle, block, SignalBlockSavedData.getSnapshot(simulator.dimension));
		}
		final boolean valid = !activeBlocks.isEmpty() && activeEnd > vehicle.head + 1.0E-6;
		final ActivityAuthorization refreshed = new ActivityAuthorization(activeStart, Math.max(activeStart, activeEnd), activeBlocks, activeFaceKeys, valid);
		if (valid) {
			vehicle.activityAuthorization = refreshed;
			vehicle.lastSafeBoundary = Math.max(vehicle.head, refreshed.endDistance());
			vehicle.lastValidActivity = refreshed;
			vehicle.activityFallbackTicks = 0;
			vehicle.lastValidActivityRequestId = vehicle.authorization.getRequestId();
			vehicle.lastValidActivityPathFingerprint = vehicle.path.getFingerprint();
			vehicle.lastValidActivityTopologyRevision = vehicle.authorization.getTopologyRevision();
			MtrbrDebugLog.event("ACTIVITY", "refresh success vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId() + " blocks=" + activeBlocks + " faces=" + activeFaceKeys);
		} else if (canReuseLastActivity(simulator, vehicle) && vehicle.head < activeEnd - 1.0E-6 && vehicle.activityFallbackTicks < 10) {
			vehicle.activityFallbackTicks++;
			vehicle.activityAuthorization = vehicle.lastValidActivity;
			MtrbrDebugLog.event("ACTIVITY", "fallback used vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId() + " reason=TRANSIENT_REFRESH_FAILURE ticks=" + vehicle.activityFallbackTicks);
		} else if (vehicle.lastValidActivity != null && canReuseLastActivity(simulator, vehicle)) {
			vehicle.activityAuthorization = vehicle.lastValidActivity;
			vehicle.activityFallbackTicks++;
			MtrbrDebugLog.event("MTRBR-AUTH-STALE", "vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId()
					+ " headDistance=" + fmt(vehicle.head) + " authorizationEnd=" + fmt(vehicle.authorizationEndDistance)
					+ " reason=ACTIVITY_REFRESH_RECOVERABLE");
			MtrbrDebugLog.event("ACTIVITY", "refresh fail vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId() + " reason=" + activityFailureReason(simulator, vehicle, activeBlocks, activeFaceKeys));
		} else {
			vehicle.activityAuthorization = refreshed;
			MtrbrDebugLog.event("MTRBR-AUTH-RECOVERY", "vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId()
					+ " reason=ACTIVITY_REFRESH_NO_REUSABLE_PREFIX");
		}
		for (final String blockId : activeBlocks) {
			final String audit = "vehicle=" + vehicle.vehicle.getId() + " blockId=" + blockId + " source=SAVED_DATA activity=true";
			MtrbrDebugLog.event("AUTH-BLOCK", audit);
			System.out.println("[MTRBR-AUTH-BLOCK] " + audit);
		}
		debugActivity(simulator, vehicle, vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream().filter(PathSnapshot::isDirectionMatched).toList());
	}

	private static boolean isTerminal(RequestState state) {
		return state == RequestState.PASSED || state == RequestState.RELEASED || state == RequestState.CANCELED
				|| state == RequestState.INVALID || state == RequestState.REVOKED;
	}

	private static void enterAuthorizationRecovery(VehicleState vehicle) {
		if (vehicle.request == null || isTerminal(vehicle.request.getState())) return;
		if (vehicle.request.getState() == RequestState.AUTHORIZED) {
			transition(vehicle.request, RequestState.ACTIVE, "Authorization recovery started");
		}
	}

	private static boolean canReuseLastActivity(Simulator simulator, VehicleState vehicle) {
		if (vehicle.lastValidActivity == null || !vehicle.lastValidActivity.valid() || vehicle.request == null || vehicle.path == null) return false;
		if (vehicle.head >= vehicle.lastValidActivity.endDistance() - 1.0E-6) return false;
		if (!vehicle.request.getRequestId().equals(vehicle.lastValidActivityRequestId) || !vehicle.path.getFingerprint().equals(vehicle.lastValidActivityPathFingerprint)) return false;
		if (vehicle.authorization != null && vehicle.authorization.getTopologyRevision() != vehicle.lastValidActivityTopologyRevision) return false;
		if (SectionStateManager.getTopologyRevision(simulator) != vehicle.lastValidActivityTopologyRevision) return false;
		final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
		return vehicle.lastValidActivity.blockIds().stream().allMatch(saved.blockRails()::containsKey);
	}

	private static String activityFailureReason(Simulator simulator, VehicleState vehicle, List<String> activeBlocks, List<PathSnapshot.FaceTraversalKey> activeFaceKeys) {
		if (vehicle.authorization == null) return "AUTHORIZATION_MISSING";
		if (SectionStateManager.getTopologyRevision(simulator) != vehicle.authorization.getTopologyRevision()) return "TOPOLOGY_REVISION_CHANGED";
		if (activeFaceKeys.isEmpty()) return "AUTHORIZED_FACE_KEYS_EMPTY";
		if (activeBlocks.isEmpty()) return "BLOCK_IDENTITY_MISMATCH";
		return "ACTIVE_WINDOW_EMPTY";
	}

	/** Stage-A read-only audit of directional candidates and generated face keys. */
	private static void auditDirection(Simulator simulator, VehicleState vehicle) {
		final String fingerprint = vehicle.path.getFingerprint() + "|" + ServerAspectManager.getFaceSnapshot(simulator.dimension).revision();
		if (fingerprint.equals(vehicle.lastDirectionAuditFingerprint)) return;
		vehicle.lastDirectionAuditFingerprint = fingerprint;
		final ServerAspectManager.FaceSnapshot topology = ServerAspectManager.getFaceSnapshot(simulator.dimension);
		final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final SignalFace face : topology.faces().values()) {
			for (final PathSnapshot.NodeDistance occurrence : vehicle.path.getNodeDistances(face.nodePos())) {
				if (occurrence.distance() < vehicle.head - 1.0E-6) continue;
				final PathSnapshot.PathTraversal traversal = vehicle.path.getTraversals().stream()
						.filter(item -> occurrence.distance() >= item.startDistance() - 1.0E-6
								&& occurrence.distance() <= item.endDistance() + 1.0E-6)
						.findFirst().orElse(null);
				if (traversal == null) continue;
				final double difference = angleDifference(occurrence.travelAngle(), face.travelAngle());
				final boolean matched = difference < 90;
				final SignalTopology.DiagnosticInfo diagnostic = SignalTopology.diagnostic(face.id());
				final String blockId = saved.getBlockId(face.id());
				final String audit = "vehicle=" + vehicle.vehicle.getId()
						+ " side=" + (face.backSide() ? "R" : "F")
						+ " PathTraversal:index=" + traversal.index() + " sectionId=" + traversal.sectionId()
						+ " start/end=" + String.format("%.1f/%.1f", traversal.startDistance(), traversal.endDistance())
						+ " travelAngle=" + String.format("%.1f", traversal.travelAngle())
						+ " SignalFace:faceId=" + face.id() + " node=" + face.nodePos()
						+ " signalAngle=" + String.format("%.1f", diagnostic.signalAngle())
						+ " reversed=" + diagnostic.reversed()
						+ " Match:matched=" + matched + " angleDifference=" + String.format("%.1f", difference)
						+ " SavedBlock:blockId=" + (blockId.isBlank() ? "<missing>" : blockId)
						+ " exists=" + !blockId.isBlank() + " railCount=" + saved.getRailIds(blockId).size();
				MtrbrDebugLog.event("DIRECTION-AUDIT", audit);
				System.out.println("[MTRBR-DIRECTION-AUDIT] " + audit);
			}
		}
		for (final PathSnapshot.FaceTraversal face : vehicle.path.getFaceTraversals(simulator.dimension, topology)) {
			final String blockId = saved.getBlockId(face.faceId());
			final String audit = "faceId=" + face.faceId()
					+ " traversalIndex=" + face.pathTraversalIndex()
					+ " occurrenceIndex=" + face.occurrenceIndex()
					+ " key=" + face.key()
					+ " blockId=" + (blockId.isBlank() ? "<missing>" : blockId);
			MtrbrDebugLog.event("FACE-KEY", audit);
			System.out.println("[MTRBR-FACE-KEY] " + audit);
		}
	}

	private static double angleDifference(double first, double second) {
		if (Double.isNaN(first) || Double.isNaN(second)) return 180;
		double difference = Math.abs((first - second) % 360);
		return difference > 180 ? 360 - difference : difference;
	}

	private static void auditAuthorizationFailure(Simulator simulator, VehicleState vehicle, Clearance clearance) {
		final List<PathSnapshot.FaceTraversal> allFaces = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		final List<PathSnapshot.FaceTraversal> faces = allFaces.stream().filter(PathSnapshot::isDirectionMatched)
				.filter(face -> face.distance() >= vehicle.controlDistance && face.distance() < vehicle.authorizationLookaheadEndDistance).toList();
		String reason = "KEY_MISMATCH";
		if (faces.isEmpty()) {
			reason = "NO_FACE_TRAVERSAL";
		} else {
			final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
			for (final PathSnapshot.FaceTraversal face : faces) {
				final SavedBlockTraversal block = savedBlockTraversal(vehicle.path, allFaces.stream().filter(PathSnapshot::isDirectionMatched).toList(), saved, face);
				if (block == null) {
					reason = "NO_BLOCK_MAPPING";
					break;
				}
				final SectionCheck.Status status = SectionCheck.checkBlock(simulator, block.blockId(), block.railIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false).status();
				if (status == SectionCheck.Status.OCCUPIED) { reason = "BLOCK_OCCUPIED"; break; }
				if (status == SectionCheck.Status.LOCKED || status == SectionCheck.Status.RESERVED || status == SectionCheck.Status.BLOCK_CONFLICT) { reason = status == SectionCheck.Status.BLOCK_CONFLICT ? "BLOCK_LOCKED" : status.name(); break; }
			}
		}
		final String audit = "vehicle=" + vehicle.vehicle.getId() + " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
				+ " reason=" + reason + " clearanceBlocks=" + clearance.blockIds();
		MtrbrDebugLog.event("AUTH-FAIL", audit);
		System.out.println("[MTRBR-AUTH-FAIL] " + audit);
	}

	private static void debugActivity(Simulator simulator, VehicleState vehicle, List<PathSnapshot.FaceTraversal> faces) {
		final ActivityAuthorization activity = vehicle.activityAuthorization;
		final String nextRed = faces.stream().filter(face -> face.distance() > vehicle.head)
				.filter(face -> activity.faceTraversalKeys().stream().noneMatch(key -> key.sameIdentity(face.key()))).map(PathSnapshot.FaceTraversal::faceId).findFirst().orElse("<end>");
		final String signature = String.format("%.1f/%.1f|%.1f/%.1f|%s|%s|%s", vehicle.head, vehicle.tail,
				activity.startDistance(), activity.endDistance(), activity.blockIds(), activity.faceTraversalKeys(), activity.valid());
		if (!signature.equals(vehicle.lastActivitySignature)) {
			vehicle.lastActivitySignature = signature;
			System.out.println("[MTRBR-ACTIVITY] vehicle=" + vehicle.vehicle.getId()
					+ " head/tail=" + String.format("%.1f/%.1f", vehicle.head, vehicle.tail)
					+ " histBlocks=" + vehicle.authorization.getBlockIds()
					+ " activeStart/end=" + String.format("%.1f/%.1f", activity.startDistance(), activity.endDistance())
					+ " activeBlocks=" + activity.blockIds() + " activeFaces=" + activity.faceTraversalKeys()
					+ " nextRed=" + nextRed + " valid=" + activity.valid());
		}
	}

	/** Releases an authorization prefix only after the vehicle tail clears each Block boundary. */
	private static void releaseAuthorizationPastHead(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) return;
		final double epsilon = 1.0E-6;
		final List<Authorization.BlockAuthorization> cleared = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> vehicle.tail >= block.endDistance() - epsilon).toList();
		if (cleared.isEmpty()) return;
		final List<Authorization.BlockAuthorization> retained = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> vehicle.tail < block.endDistance() - epsilon).toList();
		final String requestId = vehicle.request.getRequestId();
		final Set<String> retainedSections = retained.stream().flatMap(block -> block.sectionIds().stream()).collect(java.util.stream.Collectors.toSet());
		final Set<String> clearedSections = cleared.stream().flatMap(block -> block.sectionIds().stream()).collect(java.util.stream.Collectors.toSet());
		clearedSections.removeAll(retainedSections);
		clearedSections.removeAll(vehicle.sections);
		SectionStateManager.releaseSections(simulator, clearedSections, requestId);
		SectionStateManager.releaseBlocks(simulator, blockLockIds(cleared), requestId);
		for (final Authorization.BlockAuthorization block : cleared) {
			MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "block=" + block.blockId() + " section=" + block.sectionIds()
					+ " tailDistance=" + fmt(vehicle.tail) + " request=" + requestId + " reason=TAIL_PASSED_BLOCK_END");
		}
		final List<PathSnapshot.PathTraversal> clearedTraversals = cleared.stream().flatMap(block -> block.traversals().stream()).toList();
		JunctionStateManager.release(simulator, JunctionStateManager.resourcesFor(simulator, clearedTraversals), requestId);
		MtrbrDebugLog.event("MTRBR-AUTH-STALE", "vehicle=" + vehicle.vehicle.getId() + " request=" + requestId
				+ " headDistance=" + fmt(vehicle.head) + " clearedBlocks=" + cleared.stream().map(Authorization.BlockAuthorization::blockId).toList()
				+ " clearedSections=" + clearedSections + " reason=TAIL_PASSED_BLOCK_END");
		if (retained.isEmpty()) {
			vehicle.lastSafeBoundary = Math.max(vehicle.head, finiteOr(vehicle.lastSafeBoundary, vehicle.head));
			vehicle.authorizationRetryPending = true;
			enterAuthorizationRecovery(vehicle);
		}
		vehicle.authorization = retained.isEmpty() ? null : createAuthorization(simulator, STATES.get(simulator), vehicle, retained, false);
		if (!retained.isEmpty()) vehicle.authorizationRetryPending = false;
		if (!retained.isEmpty()) refreshActivityAuthorization(simulator, vehicle);
		vehicle.authorizationEndDistance = retained.stream().mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(vehicle.head);
		vehicle.lastAuthorizationId = retained.isEmpty() ? vehicle.lastAuthorizationId : vehicle.authorization.getAuthorizationId();
		logAuthorizationLifecycle(simulator, vehicle, "AUTHORIZATION_PREFIX_RELEASED");
	}

	private static void updateAuthorizedLifecycle(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request.getState() == RequestState.AUTHORIZED && vehicle.head >= vehicle.controlDistance) {
			transition(vehicle.request, RequestState.ACTIVE, "Entered authorized route");
		}
		if ((vehicle.request.getState() == RequestState.AUTHORIZED || vehicle.request.getState() == RequestState.ACTIVE) && vehicle.tail >= vehicle.endDistance) {
			transition(vehicle.request, RequestState.PASSED, "Vehicle tail passed complete Request end");
		}
		// releaseAuthorizationPastHead owns all Block/Section/Junction release.
		// Keeping that lifecycle in one place prevents a Section from being dropped
		// when its parent Block is still locked by the vehicle tail.
		if (vehicle.tail >= vehicle.endDistance) {
			release(simulator, vehicle);
			transition(vehicle.request, RequestState.RELEASED, "Vehicle tail cleared route");
		}
	}

	private static void release(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization != null && vehicle.request != null) {
			MtrbrDebugLog.event("RELEASE-RESOURCES", "vehicle=" + vehicle.request.getVehicleId()
					+ " request=" + vehicle.request.getRequestId()
					+ " sections=" + vehicle.authorization.getSectionIds()
					+ " blocks=" + vehicle.authorization.getBlockIds());
			MtrbrDebugLog.event("AUTH", "released vehicle=" + vehicle.request.getVehicleId() + " request=" + vehicle.request.getRequestId() + " rails=" + vehicle.authorization.getSectionIds());
			SectionStateManager.releaseSections(simulator, vehicle.authorization.getSectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, blockLockIds(vehicle.authorization.getBlockAuthorizations()), vehicle.request.getRequestId());
			JunctionStateManager.release(simulator, JunctionStateManager.resourcesFor(simulator, vehicle.authorization.getTraversals()), vehicle.request.getRequestId());
			vehicle.authorization = null;
		}
	}

	private static void invalidateAuthorization(Simulator simulator, VehicleState vehicle, ReleaseReason reason, RequestState terminalState) {
		final Authorization authorization = vehicle.authorization;
		final String requestId = vehicle.request == null ? "" : vehicle.request.getRequestId();
		clearOneShotOverride(simulator, vehicle, "authorization invalidated: " + reason);
		if (authorization != null) {
			vehicle.lastAuthorizationId = authorization.getAuthorizationId();
			final Set<String> pendingSections = new HashSet<>();
			for (final Authorization.BlockAuthorization block : authorization.getBlockAuthorizations()) {
				if (vehicle.tail < block.endDistance() - 1.0E-6) pendingSections.addAll(block.sectionIds());
			}
			pendingSections.addAll(vehicle.sections);
			final Set<String> immediateSections = new HashSet<>(authorization.getSectionIds());
			immediateSections.removeAll(pendingSections);
			SectionStateManager.releaseSections(simulator, immediateSections, requestId);
			final List<PathSnapshot.PathTraversal> clearedTraversals = new ArrayList<>();
			for (final Authorization.BlockAuthorization block : authorization.getBlockAuthorizations()) {
				if (vehicle.tail < block.endDistance() - 1.0E-6) {
					registerPendingRelease(simulator, vehicle, block, reason);
				} else {
					SectionStateManager.releaseBlocks(simulator, blockLockIds(List.of(block)), requestId);
					clearedTraversals.addAll(block.traversals());
				}
			}
			for (final String sectionId : pendingSections) vehicle.pendingReleaseSections.put(sectionId, reason);
			JunctionStateManager.release(simulator, JunctionStateManager.resourcesFor(simulator, clearedTraversals), requestId);
			MtrbrDebugLog.event("MTRBR-RESOURCE-PENDING-RELEASE", "vehicleId=" + (vehicle.vehicle == null ? "<none>" : vehicle.vehicle.getId())
					+ " requestId=" + requestId + " authorizationId=" + authorization.getAuthorizationId()
					+ " reason=" + reason + " sections=" + vehicle.pendingReleaseSections + " blocks=" + vehicle.pendingReleaseBlocks);
		}
		vehicle.authorization = null;
		vehicle.activityAuthorization = null;
		vehicle.authorizationRetryPending = false;
		vehicle.authorizationEndDistance = vehicle.head;
		logAuthorizationLifecycle(simulator, vehicle, "AUTHORIZATION_INVALIDATED_" + reason);
		if (vehicle.request != null && vehicle.request.getState() != terminalState && vehicle.request.getState() != RequestState.RELEASED) {
			transition(vehicle.request, terminalState, "Authorization invalidated: " + reason);
		}
	}

	private static void releaseAll(Simulator simulator, VehicleState vehicle) {
		release(simulator, vehicle);
		final String requestId = vehicle.request == null ? "" : vehicle.request.getRequestId();
		SectionStateManager.releaseSections(simulator, vehicle.pendingReleaseSections.keySet(), requestId);
		for (final String occurrenceId : vehicle.pendingReleaseBlocks.keySet()) {
			SectionStateManager.releaseBlocks(simulator, List.of(occurrenceId, vehicle.pendingBlockLogicalIds.getOrDefault(occurrenceId, occurrenceId)), requestId);
			JunctionStateManager.release(simulator, vehicle.pendingBlockJunctionResources.getOrDefault(occurrenceId, List.of()), requestId);
		}
		vehicle.pendingReleaseSections.clear();
		vehicle.pendingReleaseBlocks.clear();
		vehicle.pendingBlockEndDistances.clear();
		vehicle.pendingBlockLogicalIds.clear();
		vehicle.pendingBlockJunctionResources.clear();
		vehicle.pendingBlockSectionIds.clear();
	}

	private static void registerPendingRelease(Simulator simulator, VehicleState vehicle,
			Authorization.BlockAuthorization block, ReleaseReason reason) {
		vehicle.pendingReleaseBlocks.put(block.occurrenceId(), reason);
		vehicle.pendingBlockEndDistances.put(block.occurrenceId(), block.endDistance());
		vehicle.pendingBlockLogicalIds.put(block.occurrenceId(), block.blockId());
		vehicle.pendingBlockJunctionResources.put(block.occurrenceId(), JunctionStateManager.resourcesFor(simulator, block.traversals()));
		vehicle.pendingBlockSectionIds.put(block.occurrenceId(), List.copyOf(block.sectionIds()));
		for (final String sectionId : block.sectionIds()) {
			vehicle.pendingReleaseSections.put(sectionId, reason);
		}
		MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "vehicle=" + vehicle.vehicle.getId()
				+ " resource=block:" + block.blockId() + " tailDistance=" + fmt(vehicle.tail)
				+ " endDistance=" + fmt(block.endDistance()) + " released=false");
	}

	/** Releases a pending Block, its Sections and its Junction resources as one tail-cleared unit. */
	private static void releasePendingReleaseOccupancy(Simulator simulator, VehicleState vehicle) {
		final String requestId = vehicle.request == null ? "" : vehicle.request.getRequestId();
		final Set<String> releasedBlocks = new HashSet<>();
		for (final String occurrenceId : vehicle.pendingReleaseBlocks.keySet()) {
			if (!isBlockPhysicallyOccupied(vehicle, occurrenceId)) releasedBlocks.add(occurrenceId);
		}
		if (!releasedBlocks.isEmpty()) {
			for (final String occurrenceId : releasedBlocks) {
				SectionStateManager.releaseBlocks(simulator, List.of(occurrenceId, vehicle.pendingBlockLogicalIds.getOrDefault(occurrenceId, occurrenceId)), requestId);
				JunctionStateManager.release(simulator, vehicle.pendingBlockJunctionResources.getOrDefault(occurrenceId, List.of()), requestId);
				MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "vehicle=" + vehicle.vehicle.getId()
						+ " resource=block:" + vehicle.pendingBlockLogicalIds.getOrDefault(occurrenceId, occurrenceId)
						+ " tailDistance=" + fmt(vehicle.tail)
						+ " endDistance=" + fmt(vehicle.pendingBlockEndDistances.getOrDefault(occurrenceId, Double.NaN))
						+ " released=true");
			}
			releasedBlocks.forEach(vehicle.pendingReleaseBlocks::remove);
		}
		final Set<String> activeSections = vehicle.authorization == null ? Set.of() : new HashSet<>(vehicle.authorization.getSectionIds());
		final Set<String> protectedSections = vehicle.pendingReleaseBlocks.keySet().stream()
				.flatMap(occurrenceId -> vehicle.pendingBlockSectionIds.getOrDefault(occurrenceId, List.of()).stream())
				.collect(java.util.stream.Collectors.toSet());
		final Set<String> releasedSections = new HashSet<>(vehicle.pendingReleaseSections.keySet());
		releasedSections.removeAll(vehicle.sections);
		releasedSections.removeAll(activeSections);
		releasedSections.removeAll(protectedSections);
		if (!releasedSections.isEmpty()) {
			SectionStateManager.releaseSections(simulator, releasedSections, requestId);
			for (final String sectionId : releasedSections) {
				vehicle.pendingReleaseSections.remove(sectionId);
				MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "vehicle=" + vehicle.vehicle.getId()
						+ " resource=section:" + sectionId + " tailDistance=" + fmt(vehicle.tail)
						+ " endDistance=<cleared-block> released=true");
			}
		}
		for (final String occurrenceId : releasedBlocks) {
			vehicle.pendingBlockEndDistances.remove(occurrenceId);
			vehicle.pendingBlockLogicalIds.remove(occurrenceId);
			vehicle.pendingBlockJunctionResources.remove(occurrenceId);
			vehicle.pendingBlockSectionIds.remove(occurrenceId);
		}
	}

	private static boolean isBlockPhysicallyOccupied(VehicleState vehicle, String blockId) {
		return vehicle.tail < vehicle.pendingBlockEndDistances.getOrDefault(blockId, Double.POSITIVE_INFINITY) - 1.0E-6;
	}

	private static void transition(RouteRequest request, RequestState next, String reason) {
		if (request.getState() != next) {
			try {
				final RequestState previous = request.getState();
				request.transitionTo(next, reason);
				MtrbrDebugLog.event("REQUEST", "vehicle=" + request.getVehicleId() + " request=" + request.getRequestId() + " " + previous + "->" + next + " reason=" + reason);
			} catch (IllegalStateException ignored) {
				MtrbrDebugLog.event("REQUEST", "invalid-transition vehicle=" + request.getVehicleId() + " request=" + request.getRequestId() + " from=" + request.getState() + " to=" + next + " reason=" + reason);
			}
		}
	}

	/** One-shot forensic snapshot for an ACTIVE request invalidated during observation. */
	private static void logActivityInvalidation(Simulator simulator, VehicleState vehicle, String reason) {
		if (vehicle.request == null || vehicle.request.getState() != RequestState.ACTIVE || vehicle.authorization == null) return;
		final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
		final ActivityAuthorization activity = vehicle.activityAuthorization;
		final List<String> hist = vehicle.authorization.getBlockIds();
		final List<String> current = hist.stream().filter(saved.blockRails()::containsKey).distinct().toList();
		final List<String> missing = hist.stream().filter(id -> !saved.blockRails().containsKey(id)).distinct().toList();
		final List<PathSnapshot.FaceTraversal> faces = vehicle.path == null ? List.of() : vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream().filter(PathSnapshot::isDirectionMatched).toList();
		final List<String> before = faces.stream().filter(face -> vehicle.authorization.getFaceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key()))).map(face -> saved.getBlockId(face.faceId())).distinct().toList();
		final List<String> after = before.stream().filter(id -> !id.isBlank() && hist.contains(id)).toList();
		System.out.println("[MTRBR-ACTIVITY-INVALID] vehicle=" + vehicle.vehicle.getId()
				+ " requestId=" + vehicle.request.getRequestId()
				+ " histBlockIds=" + hist
				+ " currentSavedDataBlockIds=" + current
				+ " missingBlocks=" + missing
				+ " activeStart=" + (activity == null ? "NaN" : activity.startDistance())
				+ " activeEnd=" + (activity == null ? "NaN" : activity.endDistance())
				+ " activeBlocksBeforeFilter=" + before
				+ " activeBlocksAfterFilter=" + after
				+ " invalidReason=" + reason);
	}

	/**
	 * The departure window applies only to the first authorization from a siding.
	 * Once a request has received an authorization or entered its running phase,
	 * subsequent prefix extension and recovery must remain in normal FCFS.
	 */
	private static boolean isDepartureGuardBlocked(Simulator simulator, VehicleState vehicle) {
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
	private static boolean isDepartureWindow(Simulator simulator, VehicleState vehicle) {
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
	public static double getStopBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null || vehicle.path == null || vehicle.path.isEmpty()) {
			return Double.NaN;
		}
		if (vehicle.request != null && (vehicle.request.getState() == RequestState.INVALID || vehicle.request.getState() == RequestState.REVOKED)) {
			return vehicle.head;
		}
		if (isTurnbackHandoff(simulator, vehicleId)) {
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
		return vehicle.request == null ? Double.NaN : nonRetreatingGateBoundary(vehicle, vehicle.controlDistance, "CONTROL_DISTANCE");
	}

	/** Falls back to the currently locked prefix when Activity projection is temporarily unavailable. */
	private static double authorizationRecoveryBoundary(VehicleState vehicle, String source) {
		if (vehicle.authorization != null && vehicle.authorizationEndDistance > vehicle.head + 1.0E-6) {
			return nonRetreatingGateBoundary(vehicle, vehicle.authorizationEndDistance, source);
		}
		// No locked prefix remains. Hold at the current head until the FCFS retry
		// creates one; never fall back to a historical control distance.
		return vehicle.request == null ? Double.NaN : vehicle.head;
	}

	public static double getLastSafeBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle == null ? Double.NaN : vehicle.lastSafeBoundary;
	}

	private static double nonRetreatingGateBoundary(VehicleState vehicle, double boundary, String source) {
		if (boundary >= vehicle.head - 1.0E-6) return boundary;
		final double lastSafeBoundary = finiteOr(vehicle.lastSafeBoundary, vehicle.head);
		final double corrected = Math.max(vehicle.head, lastSafeBoundary);
		System.out.println("[MTRBR-GATE-INVALID-BOUNDARY] vehicle=" + vehicle.vehicle.getId()
				+ " headDistance=" + fmt(vehicle.head) + " boundary=" + fmt(boundary)
				+ " lastSafeBoundary=" + fmt(vehicle.lastSafeBoundary) + " source=" + source);
		return corrected;
	}

	private static double finiteOr(double value, double fallback) {
		return Double.isFinite(value) ? value : fallback;
	}

	private static double authorizedControlBoundary(Simulator simulator, VehicleState vehicle, ActivityAuthorization activity, ServerAspectManager.FaceSnapshot topology) {
		for (final PathSnapshot.FaceTraversal face : vehicle.path.getFaceTraversals(simulator.dimension, topology)) {
			if (!PathSnapshot.isDirectionMatched(face) || face.distance() <= vehicle.head + 1.0E-6) continue;
			if (face.distance() >= activity.endDistance() - 1.0E-6) return activity.endDistance();
			final Authorization.BlockAuthorization block = vehicle.authorization.getBlockAuthorizations().stream()
					.filter(candidate -> candidate.faceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key())))
					.filter(candidate -> vehicle.tail < candidate.endDistance() - 1.0E-6)
					.findFirst().orElse(null);
			if (block == null || !isBlockLocked(simulator, block, vehicle.request.getRequestId())) return face.distance();
		}
		return activity.endDistance();
	}

	private static boolean isBlockLocked(Simulator simulator, Authorization.BlockAuthorization block, String requestId) {
		final Map<String, SectionStateManager.SectionSnapshot> sections = SectionStateManager.getSections(simulator, block.sectionIds());
		return SectionStateManager.areBlocksReservedAndLockedBy(simulator, blockLockIds(List.of(block)), requestId)
				&& !block.sectionIds().isEmpty() && block.sectionIds().stream()
				.allMatch(sectionId -> sections.containsKey(sectionId)
						&& sections.get(sectionId).reservedBy.contains(requestId)
						&& sections.get(sectionId).lockedBy.contains(requestId));
	}

	private static double firstPhysicalSignalBoundary(PathSnapshot path, String dimension, ServerAspectManager.FaceSnapshot topology, double head) {
		double nearest = Double.NaN;
		for (final PathSnapshot.FaceTraversal face : path.getFaceTraversals(dimension, topology)) {
			if (PathSnapshot.isDirectionMatched(face) && face.distance() > head && (Double.isNaN(nearest) || face.distance() < nearest)) {
				nearest = face.distance();
			}
		}
		return nearest;
	}

	/** Queues an OP-approved manual override on the simulator's own thread. */
	public static void setManualDrivingOverride(Simulator simulator, long vehicleId, boolean enabled) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null) {
				vehicle.manualDrivingOverride = enabled && vehicle.vehicle.vehicleExtraData.getIsCurrentlyManual();
				vehicle.overrideState = vehicle.manualDrivingOverride ? OverrideState.MANUAL_DRIVING : (vehicle.oneShotOverride ? OverrideState.ONE_SHOT : OverrideState.NONE);
				MtrbrDebugLog.event("DISPATCH", "manual-driving-override vehicle=" + vehicleId + " enabled=" + vehicle.manualDrivingOverride);
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
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle == null || vehicle.request == null) {
				return;
			}
			if (vehicle.authorization == null) {
				grantAuthorizationPrefix(simulator, state, vehicle, "Manual dispatcher approval");
			} else {
				extendAuthorization(simulator, vehicle);
			}
			state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-approve vehicle=" + vehicleId);
		});
	}

	/** Human dispatcher revocation. Physical occupancy remains protected until the tail clears each Section. */
	public static void revokePendingAuthorization(Simulator simulator, long vehicleId) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null && vehicle.request != null) {
				invalidateAuthorization(simulator, vehicle, ReleaseReason.REVOKED, RequestState.REVOKED);
				vehicle.lastCheckedStateRevision = -1;
				vehicle.lastCheckedTick = -20;
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-revoke vehicle=" + vehicleId);
			}
		});
	}

	/** 一次性越过当前红灯：执行层放行一个信号边界后自动失效。 */
	public static void grantOneShotOverride(Simulator simulator, long vehicleId) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null && vehicle.path != null && !vehicle.path.isEmpty()) {
				final double nodeBoundary = nextBoundNodeDistance(simulator, vehicle);
				vehicle.overrideEndDistance = Double.isNaN(nodeBoundary) ? vehicle.path.getFirstSectionEndAfter(vehicle.head) : nodeBoundary;
				vehicle.oneShotOverride = true;
				vehicle.overrideState = OverrideState.ONE_SHOT;
				MtrbrDebugLog.event("OVERRIDE", "vehicle=" + vehicleId + " boundary=" + vehicle.overrideEndDistance);
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-override vehicle=" + vehicleId + " until=" + vehicle.overrideEndDistance);
			}
		});
	}

	private static void clearOneShotOverride(Simulator simulator, VehicleState vehicle, String reason) {
		if (!vehicle.oneShotOverride && vehicle.overrideState != OverrideState.ONE_SHOT) return;
		vehicle.oneShotOverride = false;
		vehicle.overrideEndDistance = Double.NaN;
		vehicle.overrideState = vehicle.manualDrivingOverride ? OverrideState.MANUAL_DRIVING : OverrideState.NONE;
		final String detail = "vehicleId=" + (vehicle.vehicle == null ? "<none>" : vehicle.vehicle.getId())
				+ " requestId=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId()) + " reason=" + reason;
		MtrbrDebugLog.event("MTRBR-OVERRIDE-CLEAR", detail);
		final State state = STATES.get(simulator);
		if (state != null) state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " override-clear " + detail);
	}

	/** The red-light boundary is the bound rail node, never the signal block position. */
	private static double nextBoundNodeDistance(Simulator simulator, VehicleState vehicle) {
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

	public static boolean hasOneShotOverride(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.oneShotOverride;
	}

	/** True only while a valid route prefix is physically reserved and locked. */
	public static boolean hasAuthorization(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.authorization != null && vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()
				&& vehicle.request != null
				&& vehicle.request.getState() != RequestState.INVALID
				&& vehicle.request.getState() != RequestState.REVOKED
				&& vehicle.activityAuthorization.endDistance() > vehicle.head;
	}

	public static double getActivityEnd(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle == null || vehicle.activityAuthorization == null || !vehicle.activityAuthorization.valid()
				? Double.NaN : vehicle.activityAuthorization.endDistance();
	}

	/** Boundary audit consumed by MovementGate; contains no path-derived signal chain. */
	public static GateBoundaryInfo getGateBoundaryInfo(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		final ActivityAuthorization activity = vehicle == null ? null : vehicle.activityAuthorization;
		if (activity == null || !activity.valid()) return new GateBoundaryInfo(Double.NaN, List.of(), "<none>", "INVALID_ACTIVITY");
		final List<String> faces = activity.faceTraversalKeys().stream().map(PathSnapshot.FaceTraversalKey::faceId).distinct().toList();
		return new GateBoundaryInfo(activity.endDistance(), faces, "<activity-end>", "ACTIVITY_END");
	}

	public record GateBoundaryInfo(double activityEnd, List<String> activityFaces, String nextSignalCandidate, String stopBoundarySource) {
		public GateBoundaryInfo { activityFaces = List.copyOf(activityFaces); }
	}

	private static void grantAuthorizationPrefix(Simulator simulator, State state, VehicleState vehicle, String reason) {
		final Clearance clearance = clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.authorizationLookaheadEndDistance);
		if (clearance.sectionIds().isEmpty()) {
			auditAuthorizationFailure(simulator, vehicle, clearance);
			transition(vehicle.request, RequestState.DENIED, "No section can be authorized");
			return;
		}
		if (!reserveAndLock(simulator, vehicle, clearance)) {
			return;
		}
		final Authorization authorization = createAuthorization(simulator, state, vehicle, clearance.blockAuthorizations(), false);
		vehicle.authorization = authorization;
		vehicle.authorizationEndDistance = clearance.endDistance();
		transition(vehicle.request, RequestState.AUTHORIZED, reason);
		logAuthorizationEnd(vehicle, "MANUAL", clearance.endDistance());
	}

	public static boolean isManualDrivingOverride(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.manualDrivingOverride && vehicle.vehicle != null && vehicle.vehicle.vehicleExtraData.getIsCurrentlyManual();
	}

	/** Whether this vehicle's movement is currently owned by the addon MovementGate. */
	public static boolean isManaged(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.managed;
	}

	/** At the turnback stop, let MTR perform its native terminate/door/reverse cycle. */
	public static boolean isTurnbackHandoff(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		final TraversalContext context = vehicle == null ? null : vehicle.traversalContext;
		return vehicle != null && context != null && context.turnbackBegun()
				&& vehicle.vehicle.vehicleExtraData.getIsTerminating()
				&& vehicle.authorization != null && vehicle.authorizationEndDistance > vehicle.head + 1.0E-3;
	}

	/**
	 * The native terminating flag is set by MTR's own block/stop calculation, so
	 * it cannot be the only condition for handing a terminal platform back to
	 * MTR. Keep the handoff tightly scoped to the final four blocks before a
	 * planned reverse boundary.
	 */
	public static boolean isApproachingPlannedTurnback(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return isApproachingPlannedTurnback(vehicle);
	}

	/** Native MTR block bypass, valid only for an explicitly approved manual vehicle. */
	public static boolean shouldBypassNativeBlock(Simulator simulator, long vehicleId) {
		return false;
	}

	public static List<AuthorizedPath> getAuthorizedPaths(Simulator simulator) {
		return AUTHORIZATION_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	/** Vehicle position snapshots used only for SignalFace -> Section ID mapping; not an occupancy source. */
	public static List<VehicleSnapshot> getVehicleSnapshots(Simulator simulator) {
		return VEHICLE_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	/** Explicit protection regeneration only: compile observed directed protection boundaries into canonical data. */
	public static Map<String, GeneratedProtection> getGeneratedProtectionBlocks(Simulator simulator, ServerAspectManager.FaceSnapshot topology) {
		final Map<String, GeneratedProtection> result = new java.util.LinkedHashMap<>();
		final Map<String, Double> boundaryDistances = new java.util.LinkedHashMap<>();
		for (final VehicleSnapshot snapshot : getVehicleSnapshots(simulator)) {
			final List<PathSnapshot.FaceTraversal> faces = snapshot.path().getFaceTraversals(simulator.dimension, topology).stream()
					.filter(PathSnapshot::isDirectionMatched).toList();
			for (final PathSnapshot.FaceTraversal first : faces) {
				final PathSnapshot.ProtectionBoundary boundary = snapshot.path().getNextProtectionBoundary(first, faces);
				if (boundary.distance() <= first.distance() + 1.0E-6) continue;
				final String blockId = first.faceId() + "->" + boundary.id();
				final List<String> rails = snapshot.path().getTraversalsBetween(first.distance(), boundary.distance()).stream()
						.map(PathSnapshot.PathTraversal::sectionId).filter(id -> !id.isBlank()).distinct().toList();
				if (rails.isEmpty()) continue;
				final GeneratedProtection candidate = new GeneratedProtection(blockId, rails, boundary.id());
				final GeneratedProtection previous = result.get(first.faceId());
				final double previousBoundaryDistance = boundaryDistances.getOrDefault(first.faceId(), Double.POSITIVE_INFINITY);
				// A face may be observed in several immutable paths. Its canonical protection
				// block is the nearest same-direction boundary, never the longest observation.
				if (previous == null || boundary.distance() < previousBoundaryDistance - 1.0E-6) {
					result.put(first.faceId(), candidate);
					boundaryDistances.put(first.faceId(), boundary.distance());
				}
			}
		}
		return Map.copyOf(result);
	}

	public record GeneratedProtection(String blockId, List<String> railIds, String boundaryId) {
		public GeneratedProtection { railIds = List.copyOf(railIds); }
	}

	public static List<RequestSnapshot> getRequestSnapshots(Simulator simulator) {
		return REQUEST_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	public static List<String> getAudit(Simulator simulator) {
		return AUDIT_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	public static String getVehicleCode(long vehicleId) {
		return VEHICLE_CODES.computeIfAbsent(vehicleId, RouteRequestManager::allocateVehicleCode);
	}

	public static Long resolveVehicleCode(String code) {
		return CODE_TO_VEHICLE.get(code);
	}

	private static String allocateVehicleCode(long ignoredVehicleId) {
		final int base = VEHICLE_CODE_ALPHABET.length();
		while (true) {
			long value = nextVehicleCode++;
			final StringBuilder code = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				code.insert(0, VEHICLE_CODE_ALPHABET.charAt((int) (value % base)));
				value /= base;
			}
			if (!CODE_TO_VEHICLE.containsKey(code.toString())) {
				CODE_TO_VEHICLE.put(code.toString(), ignoredVehicleId);
				return code.toString();
			}
		}
	}

	private static void releaseVehicleCode(long vehicleId) {
		final String code = VEHICLE_CODES.remove(vehicleId);
		if (code != null) {
			CODE_TO_VEHICLE.remove(code, vehicleId);
		}
	}

	/** Clears all request/authorization state when the server stops. */
	public static void resetAll() {
		for (final Map.Entry<Simulator, State> entry : STATES.entrySet()) {
			for (final VehicleState vehicle : entry.getValue().vehicles.values()) {
				invalidateAuthorization(entry.getKey(), vehicle, ReleaseReason.SERVER_STOP, RequestState.CANCELED);
				releaseAll(entry.getKey(), vehicle);
				clearOneShotOverride(entry.getKey(), vehicle, "server stop");
			}
		}
		STATES.clear();
		AUTHORIZATION_SNAPSHOTS = Map.of();
		VEHICLE_SNAPSHOTS = Map.of();
		REQUEST_SNAPSHOTS = Map.of();
		AUDIT_SNAPSHOTS = Map.of();
		JunctionStateManager.resetAll();
		VEHICLE_CODES.clear();
		CODE_TO_VEHICLE.clear();
		nextVehicleCode = 0;
	}

	private static void publishAuthorizations(Simulator simulator, State state) {
		final Map<Simulator, List<AuthorizedPath>> next = new IdentityHashMap<>(AUTHORIZATION_SNAPSHOTS);
		final List<AuthorizedPath> paths = new ArrayList<>();
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.authorization != null && vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()) {
				// 授权范围从“车头当前位置”开始，而不是固定从请求创建时的 controlDistance 开始；
				// 这样列车已经驶过的信号不会继续被判定为“已授权绿灯”。
				final ActivityAuthorization activity = vehicle.activityAuthorization;
				if (activity.startDistance() < activity.endDistance()) {
					final List<PathSnapshot.FaceTraversalKey> displayFaceKeys = vehicle.path
							.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
							.filter(PathSnapshot::isDirectionMatched)
							.filter(face -> face.distance() >= vehicle.head - 1.0E-6 && face.distance() < vehicle.authorizationEndDistance - 1.0E-6)
							.filter(face -> vehicle.authorization.getFaceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key())))
							.map(PathSnapshot.FaceTraversal::key).toList();
					paths.add(new AuthorizedPath(vehicle.vehicle.getId(), vehicleCode(vehicle), vehicle.path, vehicle.authorization.getTraversals(), vehicle.authorization.getFaceTraversalKeys(), activity.startDistance(), activity.endDistance(), activity.blockIds(), displayFaceKeys, vehicle.authorization.getAuthorizationId(), vehicle.authorization.getRevision()));
				}
			}
		}
		next.put(simulator, List.copyOf(paths));
		AUTHORIZATION_SNAPSHOTS = Collections.unmodifiableMap(next);

		final Map<Simulator, List<VehicleSnapshot>> nextVehicles = new IdentityHashMap<>(VEHICLE_SNAPSHOTS);
		nextVehicles.put(simulator, state.vehicles.values().stream()
				.map(vehicle -> new VehicleSnapshot(vehicle.path, vehicle.head, vehicle.tail))
				.toList());
		VEHICLE_SNAPSHOTS = Collections.unmodifiableMap(nextVehicles);

		final Map<Simulator, List<RequestSnapshot>> nextRequests = new IdentityHashMap<>(REQUEST_SNAPSHOTS);
		nextRequests.put(simulator, state.vehicles.values().stream()
				.map(vehicle -> new RequestSnapshot(vehicle.vehicle.getId(), vehicleCode(vehicle),
						vehicle.request == null ? RequestState.NONE : vehicle.request.getState(),
						vehicle.head,
						vehicle.request == null ? 0 : vehicle.controlDistance,
						vehicle.request == null ? 0 : vehicle.endDistance,
						vehicle.request == null || !Double.isFinite(vehicle.authorizationEndDistance) ? 0 : vehicle.authorizationEndDistance,
						isAuthorizationEffective(vehicle), vehicle.overrideState == OverrideState.ONE_SHOT,
						currentSpeedKmh(vehicle),
						routeName(vehicle), routeDestination(vehicle), routeNextStation(vehicle),
						countOccupiedBlocks(vehicle),
						countAuthorizedBlocks(vehicle), countLockedBlocks(simulator, vehicle)))
				.toList());
		final long snapshotTick = SectionStateManager.getCurrentTick();
		state.vehicles.values().forEach(vehicle -> {
			vehicle.lastSnapshotHead = vehicle.head;
			vehicle.lastSnapshotTick = snapshotTick;
		});
		REQUEST_SNAPSHOTS = Collections.unmodifiableMap(nextRequests);

		final Map<Simulator, List<String>> nextAudit = new IdentityHashMap<>(AUDIT_SNAPSHOTS);
		nextAudit.put(simulator, List.copyOf(state.audit));
		AUDIT_SNAPSHOTS = Collections.unmodifiableMap(nextAudit);
	}


	/** Dispatcher counts physical saved-Block occurrences, never the Rail count inside them. */
	private static int countOccupiedBlocks(VehicleState vehicle) {
		if (vehicle.authorization == null) return 0;
		return (int) vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> vehicle.tail < block.endDistance() - 1.0E-6)
				.filter(block -> vehicle.head >= block.startDistance() - 1.0E-6)
				.map(Authorization.BlockAuthorization::occurrenceId).distinct().count();
	}

	private static int countAuthorizedBlocks(VehicleState vehicle) {
		return vehicle.authorization == null ? 0 : (int) vehicle.authorization.getBlockAuthorizations().stream()
				.map(Authorization.BlockAuthorization::occurrenceId).distinct().count();
	}

	private static int countLockedBlocks(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request == null || vehicle.authorization == null) return 0;
		return (int) vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> SectionStateManager.areBlocksReservedAndLockedBy(simulator, blockLockIds(List.of(block)), vehicle.request.getRequestId()))
				.map(Authorization.BlockAuthorization::occurrenceId).distinct().count();
	}

	private static boolean isAuthorizationEffective(VehicleState vehicle) {
		return vehicle.request != null && vehicle.authorization != null
				&& vehicle.request.getState() != RequestState.INVALID
				&& vehicle.request.getState() != RequestState.REVOKED
				&& vehicle.authorizationEndDistance > vehicle.head + 1.0E-6;
	}

	private static long lastDebugMillis;

	/** 5 秒限流的服务端诊断：每辆车的请求/授权状态。 */
	private static void debugVehicles(Simulator simulator, State state) {
		final long now = System.currentTimeMillis();
		if (now - lastDebugMillis < 5000) {
			return;
		}
		lastDebugMillis = now;
		for (final VehicleState vehicle : state.vehicles.values()) {
			System.out.println("[MTRBR-REQ] sim=" + simulator.dimension
					+ " vehicle=" + vehicle.vehicle.getId()
					+ " head=" + String.format("%.1f", vehicle.head)
					+ " control=" + String.format("%.1f", vehicle.controlDistance)
					+ " end=" + String.format("%.1f", vehicle.endDistance)
					+ " req=" + (vehicle.request == null ? "-" : vehicle.request.getState())
					+ " auth=" + (vehicle.authorization != null));
		}
	}

	public record AuthorizedPath(long vehicleId, String vehicleCode, PathSnapshot path, List<PathSnapshot.PathTraversal> traversals, List<PathSnapshot.FaceTraversalKey> historicalFaceTraversalKeys, double startDistance, double endDistance, List<String> activeBlockIds, List<PathSnapshot.FaceTraversalKey> activeFaceTraversalKeys, String authorizationId, long revision) {
	}

	public record ActivityAuthorization(double startDistance, double endDistance, List<String> blockIds, List<PathSnapshot.FaceTraversalKey> faceTraversalKeys, boolean valid) {
		public ActivityAuthorization { blockIds = List.copyOf(blockIds); faceTraversalKeys = List.copyOf(faceTraversalKeys); }
	}

	public record VehicleSnapshot(PathSnapshot path, double head, double tail) {
	}

	public record RequestSnapshot(long vehicleId, String vehicleCode, RequestState state, double head, double controlDistance, double endDistance, double authorizationEndDistance, boolean authorized, boolean oneShotOverride, double speedKmh, String routeName, String destination, String nextStation, int occupiedBlocks, int authorizedBlocks, int lockedBlocks) {
	}

	private static String vehicleCode(VehicleState vehicle) {
		return getVehicleCode(vehicle.vehicle.getId());
	}

	private static double currentSpeedKmh(VehicleState vehicle) {
		final double speed = Math.max(0, ((org.mtrbr.mixin.VehicleAccess) vehicle.vehicle).mtrbr$getSpeed());
		if (speed > 0.0001) {
			return speed * 3.6;
		}
		// The dispatcher snapshot can be taken on the tick immediately after a
		// simulator update, when MTR has not published its speed field yet.  Use
		// the authoritative rail-progress delta as a short-lived display fallback.
		if (vehicle.lastSnapshotTick > 0) {
			final long tickDelta = Math.max(1, SectionStateManager.getCurrentTick() - vehicle.lastSnapshotTick);
			final double distanceDelta = Math.abs(vehicle.head - vehicle.lastSnapshotHead);
			return Math.min(300, distanceDelta / tickDelta * 20 * 3.6);
		}
		return 0;
	}

	private static String routeName(VehicleState vehicle) {
		final String name = vehicle.vehicle.vehicleExtraData.getThisRouteName();
		final String number = vehicle.vehicle.vehicleExtraData.getThisRouteNumber();
		final String route = (number == null || number.isEmpty() ? "" : number + " ") + (name == null ? "" : name);
		if (!route.isEmpty()) {
			vehicle.lastRouteName = route;
			return route;
		}
		return isApproachingPlannedTurnback(vehicle) ? vehicle.lastRouteName : "";
	}

	private static String routeDestination(VehicleState vehicle) {
		final String destination = vehicle.vehicle.vehicleExtraData.getThisRouteDestination();
		if (destination != null && !destination.isEmpty()) {
			vehicle.lastRouteDestination = destination;
			return destination;
		}
		final String nextDestination = vehicle.vehicle.vehicleExtraData.getNextRouteDestination();
		if (nextDestination != null && !nextDestination.isEmpty()) {
			vehicle.lastRouteDestination = nextDestination;
			return nextDestination;
		}
		if (!vehicle.sidingDisplay.isEmpty()) {
			return vehicle.sidingDisplay;
		}
		final String station = vehicle.vehicle.vehicleExtraData.getThisStationName();
		if (station != null && !station.isEmpty()) {
			vehicle.lastRouteDestination = station;
			return station;
		}
		return isApproachingPlannedTurnback(vehicle) ? vehicle.lastRouteDestination : "";
	}

	private static String routeNextStation(VehicleState vehicle) {
		final String station = vehicle.vehicle.vehicleExtraData.getNextStationName();
		if (station != null && !station.isEmpty()) {
			vehicle.lastNextStation = station;
			return station;
		}
		if (!vehicle.sidingDisplay.isEmpty()) {
			return vehicle.sidingDisplay;
		}
		return isApproachingPlannedTurnback(vehicle) ? vehicle.lastNextStation : "";
	}

	private static boolean isApproachingPlannedTurnback(VehicleState vehicle) {
		if (vehicle == null || vehicle.path == null || vehicle.path.isEmpty()) return false;
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(Math.max(0, vehicle.head - 4));
		return turnback.requiresTurnback()
				&& vehicle.head >= turnback.stopDistance() - 4
				&& vehicle.head <= turnback.endDistance() + 4;
	}

	private record ControlPoint(PathSnapshot.FaceTraversal traversal) {
	}

	private record ControlRange(String faceId, double controlDistance, double lookaheadEndDistance, double requestEndDistance, double triggerStart, List<String> signalFaceIds) {
	}

	private record Clearance(List<String> sectionIds, List<String> blockIds, List<PathSnapshot.PathTraversal> traversals, List<PathSnapshot.FaceTraversalKey> faceTraversalKeys, List<Authorization.BlockAuthorization> blockAuthorizations, double endDistance) {
	}

	private static List<PathSnapshot.FaceTraversalKey> combineFaceTraversalKeys(List<PathSnapshot.FaceTraversalKey> first, List<PathSnapshot.FaceTraversalKey> second) {
		final java.util.LinkedHashSet<PathSnapshot.FaceTraversalKey> keys = new java.util.LinkedHashSet<>(first);
		keys.addAll(second);
		return List.copyOf(keys);
	}

	private static SavedBlockTraversal savedBlockTraversal(PathSnapshot path, List<PathSnapshot.FaceTraversal> faces, SignalBlockSavedData.Snapshot saved, PathSnapshot.FaceTraversal face) {
		final String blockId = saved.getBlockId(face.faceId());
		final List<String> railIds = saved.getRailIds(blockId);
		if (blockId.isBlank() || railIds.isEmpty()) return null;
		final String boundaryId = saved.getBoundaryId(blockId);
		final PathSnapshot.ProtectionBoundary boundary = path.getProtectionBoundary(face, faces, boundaryId);
		if (boundary == null || boundary.distance() <= face.distance() + 1.0E-6) return null;
		return new SavedBlockTraversal(blockId, railIds, boundaryId, boundary.distance());
	}

	private record SavedBlockTraversal(String blockId, List<String> railIds, String boundaryId, double endDistance) {
	}

	private static String faceTraversalKey(PathSnapshot.FaceTraversal traversal) {
		return traversal.key().toString();
	}

	private static List<PathSnapshot.FaceTraversalKey> faceTraversalKeys(Simulator simulator, PathSnapshot path, List<PathSnapshot.PathTraversal> authorizedTraversals, double startDistance, double endDistance) {
		final Set<Integer> authorizedIndexes = authorizedTraversals.stream().map(PathSnapshot.PathTraversal::index).collect(java.util.stream.Collectors.toSet());
		return path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched)
				.filter(face -> authorizedIndexes.contains(face.pathTraversalIndex()))
				.filter(face -> face.distance() >= startDistance && face.distance() < endDistance)
				.peek(face -> MtrbrDebugLog.event("FACE-AUTH", "key=" + face.key() + " pathTraversalIndex=" + face.pathTraversalIndex() + " distance=" + face.distance() + " range=" + startDistance + ".." + endDistance))
				.map(PathSnapshot.FaceTraversal::key)
				.toList();
	}

	private static List<PathSnapshot.FaceTraversalKey> candidateFaceKeys(PathSnapshot path, List<PathSnapshot.FaceTraversal> faces,
			List<PathSnapshot.PathTraversal> traversals, double startDistance, double endDistance) {
		final Set<Integer> indexes = traversals.stream().map(PathSnapshot.PathTraversal::index).collect(java.util.stream.Collectors.toSet());
		return faces.stream().filter(PathSnapshot::isDirectionMatched)
				.filter(face -> indexes.contains(face.pathTraversalIndex()))
				.filter(face -> face.distance() >= startDistance - 1.0E-6 && face.distance() < endDistance + 1.0E-6)
				.filter(face -> Math.abs(face.distance() - startDistance) <= 1.0E-6 || face.distance() < endDistance - 1.0E-6)
				.map(PathSnapshot.FaceTraversal::key).distinct().toList();
	}

	private static void auditCandidateBlock(Simulator simulator, VehicleState vehicle, Authorization.BlockAuthorization candidate,
			SignalBlockSavedData.Snapshot savedBlocks) {
		final String vehicleId = String.valueOf(vehicle.request.getVehicleId());
		final String requestId = vehicle.request.getRequestId();
		final String candidateAudit = "vehicle=" + vehicleId + " request=" + requestId
				+ " candidateBlockId=" + candidate.blockId()
				+ " startDistance=" + fmt(candidate.startDistance()) + " endDistance=" + fmt(candidate.endDistance())
				+ " sectionIds=" + candidate.sectionIds()
				+ " traversalIndexes=" + candidate.traversals().stream().map(PathSnapshot.PathTraversal::index).toList()
				+ " faceTraversalKeys=" + candidate.faceTraversalKeys();
		MtrbrDebugLog.event("MTRBR-AUTH-CANDIDATE", candidateAudit);

		final Map<String, SectionStateManager.SectionSnapshot> sections = SectionStateManager.getSections(simulator, candidate.sectionIds());
		for (final String sectionId : candidate.sectionIds()) {
			final SectionStateManager.SectionSnapshot state = sections.get(sectionId);
			if (state == null) continue;
			final Set<Long> occupied = state.occupiedBy.stream().filter(id -> id != vehicle.request.getVehicleId()).collect(java.util.stream.Collectors.toSet());
			final Set<String> reserved = state.reservedBy.stream().filter(id -> !id.equals(requestId)).collect(java.util.stream.Collectors.toSet());
			final Set<String> locked = state.lockedBy.stream().filter(id -> !id.equals(requestId)).collect(java.util.stream.Collectors.toSet());
			if (!occupied.isEmpty() || !reserved.isEmpty() || !locked.isEmpty()) {
				final String status = !occupied.isEmpty() ? "OCCUPIED" : !reserved.isEmpty() ? "RESERVED" : "LOCKED";
				final String blockedBy = !occupied.isEmpty() ? occupied.toString() : !reserved.isEmpty() ? reserved.toString() : locked.toString();
						MtrbrDebugLog.event("MTRBR-CONFLICT", "type=SECTION vehicle=" + vehicleId
								+ " blockedBy=" + blockedBy + " candidateBlock=" + candidate.blockId()
								+ " sections=" + candidate.sectionIds() + " range=" + fmt(candidate.startDistance()) + ".." + fmt(candidate.endDistance())
								+ " state=" + status + " sectionId=" + sectionId);
			}
		}

		final State state = STATES.get(simulator);
		if (state != null) {
			for (final VehicleState other : state.vehicles.values()) {
				if (other == vehicle || other.authorization == null) continue;
				final Set<String> candidatePathNodes = new HashSet<>(vehicle.path.getPathNodesBetween(candidate.startDistance(), candidate.endDistance()));
				final Set<String> pathNodeOverlap = new HashSet<>(other.authorization.getPathNodes());
				pathNodeOverlap.retainAll(candidatePathNodes);
				for (final Authorization.BlockAuthorization owner : other.authorization.getBlockAuthorizations()) {
					final double overlapStart = Math.max(candidate.startDistance(), owner.startDistance());
					final double overlapEnd = Math.min(candidate.endDistance(), owner.endDistance());
					final boolean sectionOverlap = !Collections.disjoint(candidate.sectionIds(), owner.sectionIds());
					final boolean blockOverlap = candidate.blockId().equals(owner.blockId());
					final boolean occurrenceOverlap = candidate.traversals().stream().anyMatch(a -> owner.traversals().stream().anyMatch(b -> a.index() == b.index()));
					if (overlapEnd > overlapStart + 1.0E-6 && (sectionOverlap || blockOverlap || occurrenceOverlap)) {
						final String type = sectionOverlap ? "SECTION" : blockOverlap ? "BLOCK" : "OCCURRENCE_ONLY";
						MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=AUTHORIZATION_OVERLAP vehicle=" + vehicleId
								+ " blockedBy=" + other.vehicle.getId() + " candidateBlock=" + candidate.blockId()
								+ " ownerBlock=" + owner.blockId() + " overlapStart=" + fmt(overlapStart)
								+ " overlapEnd=" + fmt(overlapEnd) + " sectionOverlap=" + sectionOverlap
								+ " blockOverlap=" + blockOverlap + " occurrenceOverlap=" + occurrenceOverlap
								+ " conflictType=" + type);
						MtrbrDebugLog.event("MTRBR-FCFS-CONFLICT", "type=" + type + " vehicle=" + vehicleId
								+ " blockedBy=" + other.vehicle.getId() + " resource=" + owner.blockId()
								+ " range=" + fmt(overlapStart) + ".." + fmt(overlapEnd));
						MtrbrDebugLog.event("MTRBR-CONFLICT", "type=" + type + " vehicle=" + vehicleId
								+ " blockedBy=" + other.vehicle.getId() + " resource=" + owner.blockId()
								+ " range=" + fmt(overlapStart) + ".." + fmt(overlapEnd));
					}
				}
				if (!pathNodeOverlap.isEmpty()) {
					MtrbrDebugLog.event("MTRBR-CONFLICT", "type=PATH_NODE_OVERLAP_IGNORED vehicle=" + vehicleId
							+ " blockedBy=" + other.vehicle.getId() + " resource=" + pathNodeOverlap
							+ " range=" + fmt(candidate.startDistance()) + ".." + fmt(candidate.endDistance()));
				}
			}
		}
		auditBlockProjection(simulator, vehicle, candidate, savedBlocks);
	}

	private static void auditBlockProjection(Simulator simulator, VehicleState vehicle, Authorization.BlockAuthorization block,
			SignalBlockSavedData.Snapshot savedBlocks) {
		final List<PathSnapshot.FaceTraversal> allFaces = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		final Set<Integer> indexes = block.traversals().stream().map(PathSnapshot.PathTraversal::index).collect(java.util.stream.Collectors.toSet());
		for (final PathSnapshot.FaceTraversal face : allFaces.stream().filter(item -> indexes.contains(item.pathTraversalIndex())).toList()) {
			final boolean inRange = face.distance() >= block.startDistance() - 1.0E-6 && face.distance() <= block.endDistance() + 1.0E-6;
			final boolean keyProjected = block.faceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key()));
			final PathSnapshot.PathTraversal traversal = block.traversals().stream().filter(item -> item.index() == face.pathTraversalIndex()).findFirst().orElse(null);
			final boolean sectionMatched = traversal != null && block.sectionIds().contains(traversal.sectionId());
			final boolean directionMatched = PathSnapshot.isDirectionMatched(face);
			final boolean savedExists = !savedBlocks.getBlockId(face.faceId()).isBlank();
			MtrbrDebugLog.event("MTRBR-BLOCK-MATCH", "vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId()
					+ " face=" + face.key() + " pathTraversalIndex=" + face.pathTraversalIndex()
					+ " faceDistance=" + fmt(face.distance()) + " blockRange=" + fmt(block.startDistance()) + ".." + fmt(block.endDistance())
					+ " directionMatched=" + directionMatched + " sectionMatched=" + sectionMatched
					+ " keyProjected=" + keyProjected + " savedBlockExists=" + savedExists);
			if (!inRange || !sectionMatched) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=BLOCK_SECTION_MISMATCH vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
			if (!directionMatched) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=BLOCK_DIRECTION_MISMATCH vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
			if (!keyProjected) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=BLOCK_KEY_MISSING vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
			if (!savedExists) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=SAVED_BLOCK_MISSING vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
		}
	}

	private static String fmt(double value) {
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}

	private static boolean isAuthorizedFace(Authorization authorization, PathSnapshot.FaceTraversal face) {
		return authorization != null && authorization.getFaceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key()));
	}

	private static double normalizeAngle(double angle) {
		double normalized = angle % 360;
		return normalized < 0 ? normalized + 360 : normalized;
	}

	private static final class State {
		private final Map<Long, VehicleState> vehicles = new HashMap<>();
		private final List<String> audit = new ArrayList<>();
		private long authorizationRevision;
	}

	private static final class VehicleState {
		private Vehicle vehicle;
		private PathSnapshot path;
		private RouteRequest request;
		private Authorization authorization;
		private TraversalContext traversalContext;
		private Set<String> sections = Set.of();
		private double head;
		private double tail;
		private double controlDistance;
		private double endDistance;
		private double authorizationLookaheadEndDistance;
		private double authorizationEndDistance;
		private boolean authorizationRetryPending;
		private String lastAuthorizationId = "";
		private double lastSafeBoundary = Double.NaN;
		private ActivityAuthorization activityAuthorization;
		private ActivityAuthorization lastValidActivity;
		private int activityFallbackTicks;
		private String lastValidActivityRequestId = "";
		private String lastValidActivityPathFingerprint = "";
		private long lastValidActivityTopologyRevision = -1;
		private String lastActivitySignature = "";
		private String lastDirectionAuditFingerprint = "";
		private String controlFaceId = "";
		private boolean inSiding;
		private String sidingDisplay = "";
		private String lastRouteName = "";
		private String lastRouteDestination = "";
		private String lastNextStation = "";
		private double lastHead = -1;
		private double lastSnapshotHead;
		private long lastSnapshotTick;
		private long lastPassedSignalMillis;
		private long lastNoRangeDebugMillis;
		private String lastAuthorizationLookaheadSignature = "";
		private long generation;
		private boolean observed;
		private int missingTicks;
		private double lastObservedHead = Double.NaN;
		private double lastObservedTail = Double.NaN;
		private PathSnapshot lastObservedPath;
		private long lastObservedTick = -1;
		private boolean managed;
		private boolean manualDrivingOverride;
		private boolean oneShotOverride;
		private OverrideState overrideState = OverrideState.NONE;
		private double overrideEndDistance = Double.NaN;
		private final Map<String, ReleaseReason> pendingReleaseSections = new HashMap<>();
		private final Map<String, ReleaseReason> pendingReleaseBlocks = new HashMap<>();
		private final Map<String, Double> pendingBlockEndDistances = new HashMap<>();
		private final Map<String, String> pendingBlockLogicalIds = new HashMap<>();
		private final Map<String, List<String>> pendingBlockJunctionResources = new HashMap<>();
		private final Map<String, List<String>> pendingBlockSectionIds = new HashMap<>();
		private long lastCheckedStateRevision = -1;
		private long lastCheckedTick = -20;
	}

	/** Runtime-only MTR terminal state; it does not redefine immutablePath. */
	private record TraversalContext(String pathFingerprint, boolean reversed, int stopIndex, boolean terminating, boolean turnbackBegun) {
		private TraversalContext withTurnbackBegun(boolean terminating) {
			return new TraversalContext(pathFingerprint, reversed, stopIndex, terminating, true);
		}
	}
}
