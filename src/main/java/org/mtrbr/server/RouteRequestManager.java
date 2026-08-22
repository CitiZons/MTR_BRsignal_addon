package org.mtrbr.server;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtrbr.mixin.SidingAccess;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Simulation-thread owner of RouteRequest, FCFS selection and authorization lifecycles. */
public final class RouteRequestManager {
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
		if (path.isEmpty()) {
			return;
		}

		final List<PathSnapshot.FaceTraversal> faceTraversals = path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		for (final PathSnapshot.FaceTraversal faceTraversal : faceTraversals) {
			if (faceTraversal.distance() > current.lastHead && faceTraversal.distance() <= head) {
				current.lastPassedSignalMillis = System.currentTimeMillis();
			}
		}
		current.lastHead = head;
		if (current.oneShotOverride && head >= current.overrideEndDistance + 0.5) {
			current.oneShotOverride = false;
			current.overrideEndDistance = Double.NaN;
			state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-override-consumed vehicle=" + vehicle.getId());
		}

		if (!path.matchesTopology(simulator)) {
			logActivityInvalidation(simulator, current, "Path topology changed before vehicle movement");
			releaseAll(simulator, current);
			current.managed = true;
			if (current.request != null) {
				transition(current.request, RequestState.INVALID, "Path topology changed before vehicle movement");
			}
			return;
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
			release(simulator, current);
			transition(current.request, RequestState.INVALID, "MTR regenerated immutablePath");
		}
		refreshActivityAuthorization(simulator, current);

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
			releaseAll(simulator, entry.getValue());
			releaseVehicleCode(entry.getKey());
			return true;
		});
		state.vehicles.values().forEach(vehicle -> vehicle.observed = false);

		for (final VehicleState vehicle : state.vehicles.values()) {
			if (!vehicle.managed) {
				continue;
			}
			if (vehicle.request == null) {
				releaseRevokedOccupancy(simulator, vehicle);
				continue;
			}
			if (!vehicle.path.matchesTopology(simulator)) {
				releaseAll(simulator, vehicle);
				transition(vehicle.request, RequestState.INVALID, "Path topology changed");
				continue;
			}
			auditDirection(simulator, vehicle);
			if (vehicle.request.getState() == RequestState.REVOKED || vehicle.request.getState() == RequestState.CANCELED) {
				releaseRevokedOccupancy(simulator, vehicle);
				continue;
			}
			if (vehicle.authorization != null) {
				updateAuthorizedLifecycle(simulator, vehicle);
				extendAuthorization(simulator, vehicle);
				refreshActivityAuthorization(simulator, vehicle);
				continue;
			}

			final long stateRevision = SectionStateManager.getStateRevision(simulator);
			final long tick = SectionStateManager.getCurrentTick();
			if (vehicle.request.getState() == RequestState.DENIED && (vehicle.lastCheckedStateRevision != stateRevision || tick - vehicle.lastCheckedTick >= 20)) {
				transition(vehicle.request, RequestState.CHECKING, "Relevant SectionState changed");
			}
			if (vehicle.request.getState() == RequestState.DENIED || vehicle.request.getState() == RequestState.WAITING && vehicle.lastCheckedStateRevision == stateRevision) {
				continue;
			}
			final Clearance clearance = clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.authorizationLookaheadEndDistance);
			vehicle.lastCheckedStateRevision = stateRevision;
			vehicle.lastCheckedTick = tick;
			transition(vehicle.request, clearance.sectionIds().isEmpty() ? RequestState.DENIED : RequestState.WAITING,
					clearance.sectionIds().isEmpty() ? "First section unavailable" : "Waiting for FCFS");
			if (clearance.sectionIds().isEmpty()) {
				auditAuthorizationFailure(simulator, vehicle, clearance);
			}
		}

		final List<RouteRequest> waiting = new ArrayList<>();
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.request != null && vehicle.request.getState() == RequestState.WAITING) {
				waiting.add(vehicle.request);
			}
		}
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
			if (vehicle.inSiding && !isDepartureWindow(simulator, vehicle)) {
				continue;
			}

			// Request 覆盖完整进路；Authorization 只开放到第一个被占用/冲突 Section 之前，
			// 因此授权范围始终小于等于 Request 范围，不会因为前方占用而整条 DENIED。
			final Clearance clearance = clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.authorizationLookaheadEndDistance);
			final List<String> authorizedSections = clearance.sectionIds();
			if (authorizedSections.isEmpty()) {
				auditAuthorizationFailure(simulator, vehicle, clearance);
				continue;
			}
			if (!SectionStateManager.reserveBlocks(simulator, clearance.blockIds(), request.getRequestId())) {
				continue;
			}
			final Set<String> requestNodes = new HashSet<>(vehicle.path.getNodeKeysBetween(vehicle.controlDistance, clearance.endDistance()));
			boolean nodeConflict = false;
			for (final VehicleState other : state.vehicles.values()) {
				if (other == vehicle || other.authorization == null) {
					continue;
				}
				if (!Collections.disjoint(other.authorization.getNodeKeys(), requestNodes)) {
					nodeConflict = true;
					break;
				}
			}
			if (nodeConflict) {
				SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), request.getRequestId());
				continue;
			}
			final List<String> junctionResources = JunctionStateManager.resourcesFor(simulator, clearance.traversals());
			if (!JunctionStateManager.reserve(simulator, junctionResources, request.getRequestId())) {
				SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), request.getRequestId());
				continue;
			}
			if (!SectionStateManager.reserveSections(simulator, authorizedSections, request.getRequestId(), request.getVehicleId(), false)) {
				SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), request.getRequestId());
				JunctionStateManager.release(simulator, junctionResources, request.getRequestId());
				continue;
			}
			if (!SectionStateManager.lockSections(simulator, authorizedSections, request.getRequestId())) {
				SectionStateManager.releaseSections(simulator, authorizedSections, request.getRequestId());
				SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), request.getRequestId());
				JunctionStateManager.release(simulator, junctionResources, request.getRequestId());
				continue;
			}
			if (!SectionStateManager.lockBlocks(simulator, clearance.blockIds(), request.getRequestId())) {
				SectionStateManager.releaseSections(simulator, authorizedSections, request.getRequestId());
				SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), request.getRequestId());
				JunctionStateManager.release(simulator, junctionResources, request.getRequestId());
				continue;
			}
			if (!JunctionStateManager.lock(simulator, junctionResources, request.getRequestId())) {
				SectionStateManager.releaseSections(simulator, authorizedSections, request.getRequestId());
				SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), request.getRequestId());
				JunctionStateManager.release(simulator, junctionResources, request.getRequestId());
				continue;
			}
			final Authorization authorization = new Authorization(request.getRequestId() + ":auth", request.getRequestId(), authorizedSections,
					clearance.blockIds(), clearance.traversals(), clearance.faceTraversalKeys(), vehicle.path.getNodeKeysBetween(vehicle.controlDistance, clearance.endDistance()), SectionStateManager.getTopologyRevision(simulator),
					++state.authorizationRevision, false);
			for (final String blockId : authorization.getBlockIds()) {
				final String audit = "vehicle=" + request.getVehicleId() + " blockId=" + blockId + " source=SAVED_DATA";
				MtrbrDebugLog.event("AUTH-BLOCK", audit);
				System.out.println("[MTRBR-AUTH-BLOCK] " + audit);
			}
			vehicle.authorization = authorization;
			vehicle.authorizationEndDistance = clearance.endDistance();
			transition(request, RequestState.AUTHORIZED, "FCFS progressive authorization");
			MtrbrDebugLog.event("AUTH", "created source=FCFS vehicle=" + request.getVehicleId() + " request=" + request.getRequestId() + " rails=" + authorizedSections + " end=" + clearance.endDistance());
		}
		debugVehicles(simulator, state);
		publishAuthorizations(simulator, state);
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
		final double stopDistance = path.getNextOperationalStoppingDistance(controlDistance);
		final double fourthControlDistance = ahead.size() > 3 ? ahead.get(3).traversal().distance() : path.getTotalDistance();
		// This is the current look-ahead/authorization window. The Request itself
		// is created from the complete immutablePath below.
		final double lookaheadEndDistance = Math.min(stopDistance, fourthControlDistance);
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

	/** 沿 Request 区段逐 Rail 检查；Section 的唯一单位就是一条无向 Rail。 */
	private static Clearance clearancePrefix(Simulator simulator, VehicleState vehicle, double startDistance, double endDistance) {
		final List<String> authorizedRailIds = new ArrayList<>();
		final List<String> authorizedBlockIds = new ArrayList<>();
		final List<PathSnapshot.PathTraversal> authorizedTraversals = new ArrayList<>();
		final List<PathSnapshot.FaceTraversalKey> authorizedFaces = new ArrayList<>();
		double authorizedEnd = startDistance;
		final List<PathSnapshot.FaceTraversal> faces = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched).toList();
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final PathSnapshot.FaceTraversal face : faces) {
			if (face.distance() < startDistance || face.distance() >= endDistance) continue;
			final SavedBlockTraversal block = savedBlockTraversal(faces, savedBlocks, face);
			if (block == null) {
				final String audit = "face=" + face.faceId() + " blockId=<missing> nextFace=<unknown> railCount=0";
				MtrbrDebugLog.event("BLOCK-ID", audit + " source=SAVED_DATA");
				System.out.println("[MTRBR-BLOCK] " + audit);
				break;
			}
			final String blockAudit = "face=" + face.faceId() + " blockId=" + block.blockId() + " nextFace=" + faces.stream().filter(candidate -> candidate.distance() > face.distance()).map(PathSnapshot.FaceTraversal::faceId).findFirst().orElse("<unknown>") + " railCount=" + block.railIds().size();
			MtrbrDebugLog.event("BLOCK-ID", blockAudit + " rails=" + block.railIds() + " source=SAVED_DATA");
			System.out.println("[MTRBR-BLOCK] " + blockAudit);
			final SectionCheck.BlockResult check = SectionCheck.checkBlock(simulator, block.blockId(), block.railIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false);
			if (!check.safe()) {
				final SectionCheck.SectionResult result = check.sections().sections().stream().filter(item -> item.status() != SectionCheck.Status.AVAILABLE).findFirst().orElse(null);
				MtrbrDebugLog.event("CHECK", "vehicle=" + vehicle.request.getVehicleId()
						+ " request=" + vehicle.request.getRequestId()
						+ " block=" + block.blockId() + " rails=" + block.railIds()
						+ " status=" + check.status()
						+ " rail=" + (result == null ? "-" : result.sectionId()));
				break;
			}
			authorizedBlockIds.add(block.blockId());
			for (final String railId : block.railIds()) {
				if (!authorizedRailIds.contains(railId)) {
					authorizedRailIds.add(railId);
				}
			}
			for (final PathSnapshot.PathTraversal traversal : vehicle.path.getTraversalsBetween(face.distance(), block.endDistance())) {
				if (block.railIds().contains(traversal.sectionId()) && authorizedTraversals.stream().noneMatch(item -> item.index() == traversal.index())) {
					authorizedTraversals.add(traversal);
				}
			}
			authorizedFaces.add(face.key());
			authorizedEnd = Math.max(authorizedEnd, block.endDistance());
		}
		return new Clearance(List.copyOf(authorizedRailIds), List.copyOf(authorizedBlockIds), List.copyOf(authorizedTraversals), List.copyOf(authorizedFaces), authorizedEnd);
	}

	/** 在已授权前缀之后继续尝试锁闭下一段空闲 Section，使 Authorization 随列车推进动态扩展。 */
	private static void extendAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) {
			return;
		}
		refreshAuthorizationLookahead(simulator, vehicle);
		if (vehicle.authorizationEndDistance >= vehicle.authorizationLookaheadEndDistance) return;
		final Clearance extension = clearancePrefix(simulator, vehicle, vehicle.authorizationEndDistance, vehicle.authorizationLookaheadEndDistance);
		if (extension.sectionIds().isEmpty() || extension.traversals().isEmpty()) {
			return;
		}

		final Set<String> extensionNodes = new HashSet<>(vehicle.path.getNodeKeysBetween(vehicle.authorizationEndDistance, extension.endDistance()));
		final State state = STATES.get(simulator);
		if (state != null) {
			for (final VehicleState other : state.vehicles.values()) {
				if (other == vehicle || other.authorization == null) {
					continue;
				}
				if (!Collections.disjoint(other.authorization.getNodeKeys(), extensionNodes)) {
					return;
				}
			}
		}
		final List<String> junctionResources = JunctionStateManager.resourcesFor(simulator, extension.traversals());
		if (!JunctionStateManager.reserve(simulator, junctionResources, vehicle.request.getRequestId())) {
			return;
		}
		if (!SectionStateManager.reserveBlocks(simulator, extension.blockIds(), vehicle.request.getRequestId())) {
			JunctionStateManager.release(simulator, junctionResources, vehicle.request.getRequestId());
			return;
		}

		if (!SectionStateManager.reserveSections(simulator, extension.sectionIds(), vehicle.request.getRequestId(), vehicle.request.getVehicleId(), false)) {
			SectionStateManager.releaseBlocks(simulator, extension.blockIds(), vehicle.request.getRequestId());
			JunctionStateManager.release(simulator, junctionResources, vehicle.request.getRequestId());
			return;
		}
		if (!SectionStateManager.lockSections(simulator, extension.sectionIds(), vehicle.request.getRequestId())) {
			SectionStateManager.releaseSections(simulator, extension.sectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, extension.blockIds(), vehicle.request.getRequestId());
			JunctionStateManager.release(simulator, junctionResources, vehicle.request.getRequestId());
			return;
		}
		if (!SectionStateManager.lockBlocks(simulator, extension.blockIds(), vehicle.request.getRequestId())) {
			SectionStateManager.releaseSections(simulator, extension.sectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, extension.blockIds(), vehicle.request.getRequestId());
			JunctionStateManager.release(simulator, junctionResources, vehicle.request.getRequestId());
			return;
		}
		if (!JunctionStateManager.lock(simulator, junctionResources, vehicle.request.getRequestId())) {
			SectionStateManager.releaseSections(simulator, extension.sectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, extension.blockIds(), vehicle.request.getRequestId());
			JunctionStateManager.release(simulator, junctionResources, vehicle.request.getRequestId());
			return;
		}

		final Set<String> combinedSections = new java.util.LinkedHashSet<>(vehicle.authorization.getSectionIds());
		combinedSections.addAll(extension.sectionIds());
		final Set<String> combinedBlocks = new java.util.LinkedHashSet<>(vehicle.authorization.getBlockIds());
		combinedBlocks.addAll(extension.blockIds());
		final Set<String> combinedNodes = new java.util.LinkedHashSet<>(vehicle.authorization.getNodeKeys());
		combinedNodes.addAll(vehicle.path.getNodeKeysBetween(vehicle.authorizationEndDistance, extension.endDistance()));
		final List<PathSnapshot.PathTraversal> combinedTraversals = new ArrayList<>(vehicle.authorization.getTraversals());
		for (final PathSnapshot.PathTraversal traversal : extension.traversals()) {
			if (combinedTraversals.stream().noneMatch(existing -> existing.index() == traversal.index())) {
				combinedTraversals.add(traversal);
			}
		}
		final Authorization extended = new Authorization(vehicle.request.getRequestId() + ":auth", vehicle.request.getRequestId(),
				List.copyOf(combinedSections), List.copyOf(combinedBlocks), List.copyOf(combinedTraversals), combineFaceTraversalKeys(vehicle.authorization.getFaceTraversalKeys(), extension.faceTraversalKeys()), List.copyOf(combinedNodes), SectionStateManager.getTopologyRevision(simulator),
				state == null ? vehicle.authorization.getRevision() + 1 : ++state.authorizationRevision, false);
			for (final String blockId : extension.blockIds()) {
				final String audit = "vehicle=" + vehicle.request.getVehicleId() + " blockId=" + blockId + " source=SAVED_DATA";
				MtrbrDebugLog.event("AUTH-BLOCK", audit);
				System.out.println("[MTRBR-AUTH-BLOCK] " + audit);
			}
		vehicle.authorization = extended;
		vehicle.authorizationEndDistance = extension.endDistance();
		MtrbrDebugLog.event("AUTH", "extended vehicle=" + vehicle.request.getVehicleId() + " request=" + vehicle.request.getRequestId() + " addedRails=" + extension.sectionIds() + " end=" + extension.endDistance());
	}

	/** Recompute the moving authorization preview from the current vehicle position. */
	private static void refreshAuthorizationLookahead(Simulator simulator, VehicleState vehicle) {
		final List<PathSnapshot.FaceTraversal> ahead = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched)
				.filter(face -> face.distance() > vehicle.head + 1.0E-6)
				.toList();
		final double nextStop = vehicle.path.getNextOperationalStoppingDistance(vehicle.head);
		final double fourthControl = ahead.size() > 3 ? ahead.get(3).distance() : vehicle.path.getTotalDistance();
		final double recomputedEnd = Math.min(nextStop, fourthControl);
		// Never retract an already granted prefix; a stop only caps the current
		// preview and the following tick recomputes past that stop after departure.
		vehicle.authorizationLookaheadEndDistance = Math.min(vehicle.endDistance,
				Math.max(vehicle.authorizationEndDistance, recomputedEnd));
	}

	/** Projects the cumulative Authorization onto the current operational window. */
	private static void refreshActivityAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.path == null) {
			vehicle.activityAuthorization = null;
			vehicle.lastValidActivity = null;
			MtrbrDebugLog.event("ACTIVITY", "refresh fail vehicle=" + vehicle.vehicle.getId() + " reason=NO_AUTHORIZATION_OR_PATH");
			return;
		}
		final List<PathSnapshot.FaceTraversal> faces = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(PathSnapshot::isDirectionMatched).toList();
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		final List<PathSnapshot.FaceTraversal> authorizedFaces = faces.stream()
				.filter(face -> vehicle.authorization.getFaceTraversalKeys().contains(face.key()))
				.filter(face -> vehicle.authorization.getBlockIds().contains(savedBlocks.getBlockId(face.faceId())))
				.toList();
		final double activeStart = authorizedFaces.stream().filter(face -> face.distance() > vehicle.tail + 1.0E-6)
				.mapToDouble(PathSnapshot.FaceTraversal::distance).min().orElse(vehicle.head);
		final List<PathSnapshot.FaceTraversal> ahead = faces.stream().filter(face -> face.distance() > vehicle.head + 1.0E-6).toList();
		final double stop = vehicle.path.getNextOperationalStoppingDistance(vehicle.head);
		final double fourthFace = ahead.size() > 3 ? ahead.get(3).distance() : vehicle.path.getTotalDistance();
		// The activity boundary is the end of the furthest persisted block that is
		// actually authorized. Using the face distance here drops the last locked
		// block because its entry face is exactly at the old boundary.
		final double furthestLockedEnd = authorizedFaces.stream()
				.map(face -> savedBlockTraversal(faces, savedBlocks, face))
				.filter(Objects::nonNull)
				.mapToDouble(SavedBlockTraversal::endDistance)
				.max().orElse(activeStart);
		final double activeEnd = Math.min(Math.min(stop, fourthFace), furthestLockedEnd);
		final List<String> activeBlocks = authorizedFaces.stream()
				.filter(face -> face.distance() >= activeStart && face.distance() < activeEnd)
				.map(face -> savedBlocks.getBlockId(face.faceId())).filter(blockId -> !blockId.isBlank()).distinct().toList();
		final List<PathSnapshot.FaceTraversalKey> activeFaceKeys = authorizedFaces.stream()
				.filter(face -> face.distance() >= activeStart && face.distance() < activeEnd)
				.map(PathSnapshot.FaceTraversal::key).toList();
		final boolean valid = activeEnd > activeStart + 1.0E-6 && !activeFaceKeys.isEmpty() && !activeBlocks.isEmpty();
		final ActivityAuthorization refreshed = new ActivityAuthorization(activeStart, Math.max(activeStart, activeEnd), activeBlocks, activeFaceKeys, valid);
		if (valid) {
			vehicle.activityAuthorization = refreshed;
			vehicle.lastValidActivity = refreshed;
			vehicle.lastValidActivityRequestId = vehicle.authorization.getRequestId();
			vehicle.lastValidActivityPathFingerprint = vehicle.path.getFingerprint();
			vehicle.lastValidActivityTopologyRevision = vehicle.authorization.getTopologyRevision();
			MtrbrDebugLog.event("ACTIVITY", "refresh success vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId() + " blocks=" + activeBlocks + " faces=" + activeFaceKeys);
		} else if (canReuseLastActivity(simulator, vehicle)) {
			vehicle.activityAuthorization = vehicle.lastValidActivity;
			MtrbrDebugLog.event("ACTIVITY", "fallback used vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId() + " reason=TRANSIENT_REFRESH_FAILURE");
		} else {
			vehicle.activityAuthorization = refreshed;
			MtrbrDebugLog.event("ACTIVITY", "refresh fail vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId() + " reason=" + activityFailureReason(simulator, vehicle, activeBlocks, activeFaceKeys));
		}
		for (final String blockId : activeBlocks) {
			final String audit = "vehicle=" + vehicle.vehicle.getId() + " blockId=" + blockId + " source=SAVED_DATA activity=true";
			MtrbrDebugLog.event("AUTH-BLOCK", audit);
			System.out.println("[MTRBR-AUTH-BLOCK] " + audit);
		}
		debugActivity(simulator, vehicle, faces);
	}

	private static boolean canReuseLastActivity(Simulator simulator, VehicleState vehicle) {
		if (vehicle.lastValidActivity == null || !vehicle.lastValidActivity.valid() || vehicle.authorization == null || vehicle.path == null) return false;
		if (!vehicle.authorization.getRequestId().equals(vehicle.lastValidActivityRequestId) || !vehicle.path.getFingerprint().equals(vehicle.lastValidActivityPathFingerprint)) return false;
		if (vehicle.authorization.getTopologyRevision() != vehicle.lastValidActivityTopologyRevision || SectionStateManager.getTopologyRevision(simulator) != vehicle.lastValidActivityTopologyRevision) return false;
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
				final SavedBlockTraversal block = savedBlockTraversal(allFaces.stream().filter(PathSnapshot::isDirectionMatched).toList(), saved, face);
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
				.filter(face -> !activity.faceTraversalKeys().contains(face.key())).map(PathSnapshot.FaceTraversal::faceId).findFirst().orElse("<end>");
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

	private static void updateAuthorizedLifecycle(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request.getState() == RequestState.AUTHORIZED && vehicle.head >= vehicle.controlDistance) {
			transition(vehicle.request, RequestState.ACTIVE, "Entered authorized route");
		}
		if ((vehicle.request.getState() == RequestState.AUTHORIZED || vehicle.request.getState() == RequestState.ACTIVE) && vehicle.tail >= vehicle.endDistance) {
			transition(vehicle.request, RequestState.PASSED, "Vehicle tail passed complete Request end");
		}
		// A repeated Rail remains protected until every uncleared traversal using it
		// has cleared. Traversal order, rather than a Rail maximum, is authoritative.
		final Set<String> retainedSections = new HashSet<>();
		for (final PathSnapshot.PathTraversal traversal : vehicle.authorization.getTraversals()) {
			if (vehicle.tail < traversal.endDistance()) {
				retainedSections.add(traversal.sectionId());
			}
		}
		final Set<String> releasable = new HashSet<>(vehicle.authorization.getSectionIds());
		releasable.removeAll(retainedSections);
		if (!releasable.isEmpty()) {
			SectionStateManager.releaseSections(simulator, releasable, vehicle.request.getRequestId());
		}
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
			SectionStateManager.releaseBlocks(simulator, vehicle.authorization.getBlockIds(), vehicle.request.getRequestId());
			JunctionStateManager.release(simulator, JunctionStateManager.resourcesFor(simulator, vehicle.authorization.getTraversals()), vehicle.request.getRequestId());
			vehicle.authorization = null;
		}
	}

	private static void releaseAll(Simulator simulator, VehicleState vehicle) {
		release(simulator, vehicle);
		if (!vehicle.revokedSections.isEmpty()) {
			MtrbrDebugLog.event("RELEASE-RESOURCES", "vehicle=" + (vehicle.request == null ? "<none>" : vehicle.request.getVehicleId())
					+ " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
					+ " sections=" + vehicle.revokedSections + " blocks=" + vehicle.revokedBlocks);
			SectionStateManager.releaseSections(simulator, vehicle.revokedSections, vehicle.request == null ? "" : vehicle.request.getRequestId());
			vehicle.revokedSections.clear();
		}
		if (!vehicle.revokedBlocks.isEmpty()) {
			SectionStateManager.releaseBlocks(simulator, vehicle.revokedBlocks, vehicle.request == null ? "" : vehicle.request.getRequestId());
			vehicle.revokedBlocks.clear();
		}
	}

	/** Keeps revoked locks on physically occupied rails until the vehicle tail clears them. */
	private static void releaseRevokedOccupancy(Simulator simulator, VehicleState vehicle) {
		if (vehicle.revokedSections.isEmpty() && vehicle.revokedBlocks.isEmpty()) return;
		final Set<String> toRelease = new HashSet<>(vehicle.revokedSections);
		toRelease.removeAll(vehicle.sections);
		if (!toRelease.isEmpty()) {
			SectionStateManager.releaseSections(simulator, toRelease, vehicle.request == null ? "" : vehicle.request.getRequestId());
			vehicle.revokedSections.removeAll(toRelease);
		}
		if (!vehicle.revokedBlocks.isEmpty()) {
			final Set<String> toReleaseBlocks = new HashSet<>(vehicle.revokedBlocks);
			if (vehicle.sections.isEmpty() && !toReleaseBlocks.isEmpty()) {
				SectionStateManager.releaseBlocks(simulator, toReleaseBlocks, vehicle.request == null ? "" : vehicle.request.getRequestId());
				vehicle.revokedBlocks.removeAll(toReleaseBlocks);
			}
		}
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
		final List<String> before = faces.stream().filter(face -> vehicle.authorization.getFaceTraversalKeys().contains(face.key())).map(face -> saved.getBlockId(face.faceId())).distinct().toList();
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

	/** 出库时刻表窗口：车库车在预计发车前 10 秒内才允许授权出库信号。 */
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

		final ServerAspectManager.FaceSnapshot faceSnapshot = ServerAspectManager.getFaceSnapshot(simulator.dimension);
		if (faceSnapshot.faces().isEmpty()) {
			// Signal topology is temporarily unavailable. The documented default
			// is fail-closed: hold the vehicle at its current head position until
			// the server publishes a valid SignalFace topology.
			return vehicle.head;
		}
		double signalBoundary = Double.NaN;
		if (vehicle.authorization == null) {
			if (vehicle.request != null) {
				signalBoundary = vehicle.controlDistance;
			}
		} else {
			final ActivityAuthorization activity = vehicle.activityAuthorization;
			if (activity == null || !activity.valid()) return vehicle.head;
			// MovementGate has one authorized boundary: Activity.endDistance. It must
			// not derive a second signal chain from PathSnapshot or occupancy state.
			signalBoundary = activity.endDistance();
		}
		return signalBoundary;
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
				final String requestId = vehicle.request == null ? "" : vehicle.request.getRequestId();
				if (vehicle.authorization != null) {
					final Set<String> occupied = new HashSet<>(vehicle.sections);
					final Set<String> retained = new HashSet<>(vehicle.authorization.getSectionIds());
					retained.retainAll(occupied);
					final Set<String> releasable = new HashSet<>(vehicle.authorization.getSectionIds());
					releasable.removeAll(retained);
					if (!releasable.isEmpty()) {
						SectionStateManager.releaseSections(simulator, releasable, requestId);
					}
					JunctionStateManager.release(simulator, JunctionStateManager.resourcesFor(simulator, vehicle.authorization.getTraversals()), requestId);
					vehicle.revokedSections.addAll(retained);
					vehicle.revokedBlocks.addAll(vehicle.authorization.getBlockIds());
					vehicle.authorization = null;
					vehicle.authorizationEndDistance = vehicle.head;
				}
				transition(vehicle.request, RequestState.REVOKED, "Manual dispatcher revocation");
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
				MtrbrDebugLog.event("OVERRIDE", "vehicle=" + vehicleId + " boundary=" + vehicle.overrideEndDistance);
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-override vehicle=" + vehicleId + " until=" + vehicle.overrideEndDistance);
			}
		});
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
		final Set<String> requestNodes = new HashSet<>(vehicle.path.getNodeKeysBetween(vehicle.controlDistance, clearance.endDistance()));
		for (final VehicleState other : state.vehicles.values()) {
			if (other == vehicle || other.authorization == null) {
				continue;
			}
		if (!Collections.disjoint(other.authorization.getNodeKeys(), requestNodes)) {
				return;
			}
		}
		if (!SectionStateManager.reserveBlocks(simulator, clearance.blockIds(), vehicle.request.getRequestId())) {
			return;
		}
		if (!SectionStateManager.reserveSections(simulator, clearance.sectionIds(), vehicle.request.getRequestId(), vehicle.request.getVehicleId(), false)) {
			SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), vehicle.request.getRequestId());
			return;
		}
		if (!SectionStateManager.lockSections(simulator, clearance.sectionIds(), vehicle.request.getRequestId())) {
			SectionStateManager.releaseSections(simulator, clearance.sectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), vehicle.request.getRequestId());
			return;
		}
		if (!SectionStateManager.lockBlocks(simulator, clearance.blockIds(), vehicle.request.getRequestId())) {
			SectionStateManager.releaseSections(simulator, clearance.sectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), vehicle.request.getRequestId());
			return;
		}
		final List<String> junctionResources = JunctionStateManager.resourcesFor(simulator, clearance.traversals());
		if (!JunctionStateManager.reserve(simulator, junctionResources, vehicle.request.getRequestId()) || !JunctionStateManager.lock(simulator, junctionResources, vehicle.request.getRequestId())) {
			SectionStateManager.releaseSections(simulator, clearance.sectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, clearance.blockIds(), vehicle.request.getRequestId());
			JunctionStateManager.release(simulator, junctionResources, vehicle.request.getRequestId());
			return;
		}
		final Authorization authorization = new Authorization(vehicle.request.getRequestId() + ":auth", vehicle.request.getRequestId(), clearance.sectionIds(),
				clearance.blockIds(), clearance.traversals(), faceTraversalKeys(simulator, vehicle.path, clearance.traversals(), vehicle.controlDistance, clearance.endDistance()), vehicle.path.getNodeKeysBetween(vehicle.controlDistance, clearance.endDistance()), SectionStateManager.getTopologyRevision(simulator),
				++state.authorizationRevision, false);
		vehicle.authorization = authorization;
		vehicle.authorizationEndDistance = clearance.endDistance();
		transition(vehicle.request, RequestState.AUTHORIZED, reason);
		MtrbrDebugLog.event("AUTH", "created source=MANUAL vehicle=" + vehicle.request.getVehicleId() + " request=" + vehicle.request.getRequestId() + " rails=" + clearance.sectionIds() + " end=" + clearance.endDistance());
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

	/** Explicit protection regeneration only: compile observed same-direction FaceTraversal pairs into canonical A->B data. */
	public static Map<String, GeneratedProtection> getGeneratedProtectionBlocks(Simulator simulator, ServerAspectManager.FaceSnapshot topology) {
		final Map<String, GeneratedProtection> result = new java.util.LinkedHashMap<>();
		for (final VehicleSnapshot snapshot : getVehicleSnapshots(simulator)) {
			final List<PathSnapshot.FaceTraversal> faces = snapshot.path().getFaceTraversals(simulator.dimension, topology).stream()
					.filter(PathSnapshot::isDirectionMatched).toList();
			for (int index = 0; index + 1 < faces.size(); index++) {
				final PathSnapshot.FaceTraversal first = faces.get(index);
				final PathSnapshot.FaceTraversal second = faces.get(index + 1);
				final String blockId = first.faceId() + "->" + second.faceId();
				final List<String> rails = snapshot.path().getTraversalsBetween(first.distance(), second.distance()).stream()
						.map(PathSnapshot.PathTraversal::sectionId).filter(id -> !id.isBlank()).distinct().toList();
				if (rails.isEmpty()) continue;
				final GeneratedProtection candidate = new GeneratedProtection(blockId, rails, second.faceId());
				final GeneratedProtection previous = result.get(first.faceId());
				if (previous == null || candidate.railIds().size() > previous.railIds().size()) result.put(first.faceId(), candidate);
			}
		}
		return Map.copyOf(result);
	}

	public record GeneratedProtection(String blockId, List<String> railIds) {
		public GeneratedProtection { railIds = List.copyOf(railIds); }
		public GeneratedProtection(String blockId, List<String> railIds, String nextFace) {
			this(blockId, railIds);
		}
		public String nextFace() { final int separator = blockId.indexOf("->"); return separator < 0 ? "" : blockId.substring(separator + 2); }
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
					paths.add(new AuthorizedPath(vehicle.vehicle.getId(), vehicleCode(vehicle), vehicle.path, vehicle.authorization.getTraversals(), vehicle.authorization.getFaceTraversalKeys(), activity.startDistance(), activity.endDistance(), activity.blockIds(), activity.faceTraversalKeys(), vehicle.authorization.getAuthorizationId(), vehicle.authorization.getRevision()));
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
						vehicle.oneShotOverride ? RequestState.OVERRIDE : (vehicle.request == null ? RequestState.NONE : vehicle.request.getState()),
						vehicle.head,
						vehicle.request == null ? 0 : vehicle.controlDistance,
						vehicle.request == null ? 0 : vehicle.endDistance,
						vehicle.request == null || !Double.isFinite(vehicle.authorizationEndDistance) ? 0 : vehicle.authorizationEndDistance,
						isAuthorizationEffective(vehicle),
						currentSpeedKmh(vehicle),
						routeName(vehicle), routeDestination(vehicle), routeNextStation(vehicle),
						vehicle.sections.size(),
						countReservedSections(simulator, vehicle), countLockedSections(simulator, vehicle)))
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


	private static int countReservedSections(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request == null || vehicle.authorization == null) return 0;
		return (int) SectionStateManager.getSections(simulator, vehicle.authorization.getSectionIds()).values().stream()
				.filter(section -> section.reservedBy.contains(vehicle.request.getRequestId())).count();
	}

	private static int countLockedSections(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request == null || vehicle.authorization == null) return 0;
		return (int) SectionStateManager.getSections(simulator, vehicle.authorization.getSectionIds()).values().stream()
				.filter(section -> section.lockedBy.contains(vehicle.request.getRequestId())).count();
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

	public record RequestSnapshot(long vehicleId, String vehicleCode, RequestState state, double head, double controlDistance, double endDistance, double authorizationEndDistance, boolean authorized, double speedKmh, String routeName, String destination, String nextStation, int occupiedSections, int reservedSections, int lockedSections) {
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
		return (number == null || number.isEmpty() ? "" : number + " ") + (name == null ? "" : name);
	}

	private static String routeDestination(VehicleState vehicle) {
		final String destination = vehicle.vehicle.vehicleExtraData.getThisRouteDestination();
		if (destination != null && !destination.isEmpty()) {
			return destination;
		}
		final String nextDestination = vehicle.vehicle.vehicleExtraData.getNextRouteDestination();
		if (nextDestination != null && !nextDestination.isEmpty()) {
			return nextDestination;
		}
		if (!vehicle.sidingDisplay.isEmpty()) {
			return vehicle.sidingDisplay;
		}
		final String station = vehicle.vehicle.vehicleExtraData.getThisStationName();
		return station == null ? "" : station;
	}

	private static String routeNextStation(VehicleState vehicle) {
		final String station = vehicle.vehicle.vehicleExtraData.getNextStationName();
		if (station != null && !station.isEmpty()) {
			return station;
		}
		if (!vehicle.sidingDisplay.isEmpty()) {
			return vehicle.sidingDisplay;
		}
		return "";
	}

	private record ControlPoint(PathSnapshot.FaceTraversal traversal) {
	}

	private record ControlRange(String faceId, double controlDistance, double lookaheadEndDistance, double requestEndDistance, double triggerStart, List<String> signalFaceIds) {
	}

	private record Clearance(List<String> sectionIds, List<String> blockIds, List<PathSnapshot.PathTraversal> traversals, List<PathSnapshot.FaceTraversalKey> faceTraversalKeys, double endDistance) {
	}

	private static List<PathSnapshot.FaceTraversalKey> combineFaceTraversalKeys(List<PathSnapshot.FaceTraversalKey> first, List<PathSnapshot.FaceTraversalKey> second) {
		final java.util.LinkedHashSet<PathSnapshot.FaceTraversalKey> keys = new java.util.LinkedHashSet<>(first);
		keys.addAll(second);
		return List.copyOf(keys);
	}

	private static SavedBlockTraversal savedBlockTraversal(List<PathSnapshot.FaceTraversal> faces, SignalBlockSavedData.Snapshot saved, PathSnapshot.FaceTraversal face) {
		final String blockId = saved.getBlockId(face.faceId());
		final List<String> railIds = saved.getRailIds(blockId);
		if (blockId.isBlank() || railIds.isEmpty()) return null;
		final double endDistance = faces.stream().filter(candidate -> candidate.distance() > face.distance())
				.mapToDouble(PathSnapshot.FaceTraversal::distance).findFirst().orElse(Double.POSITIVE_INFINITY);
		return new SavedBlockTraversal(blockId, railIds, endDistance);
	}

	private record SavedBlockTraversal(String blockId, List<String> railIds, double endDistance) {
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

	private static boolean isAuthorizedFace(Authorization authorization, PathSnapshot.FaceTraversal face) {
		return authorization != null && authorization.getFaceTraversalKeys().contains(face.key());
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
		private Set<String> sections = Set.of();
		private double head;
		private double tail;
		private double controlDistance;
		private double endDistance;
		private double authorizationLookaheadEndDistance;
		private double authorizationEndDistance;
		private ActivityAuthorization activityAuthorization;
		private ActivityAuthorization lastValidActivity;
		private String lastValidActivityRequestId = "";
		private String lastValidActivityPathFingerprint = "";
		private long lastValidActivityTopologyRevision = -1;
		private String lastActivitySignature = "";
		private String lastDirectionAuditFingerprint = "";
		private String controlFaceId = "";
		private boolean inSiding;
		private String sidingDisplay = "";
		private double lastHead = -1;
		private double lastSnapshotHead;
		private long lastSnapshotTick;
		private long lastPassedSignalMillis;
		private long lastNoRangeDebugMillis;
		private long generation;
		private boolean observed;
		private boolean managed;
		private boolean manualDrivingOverride;
		private boolean oneShotOverride;
		private double overrideEndDistance = Double.NaN;
		private final Set<String> revokedSections = new HashSet<>();
		private final Set<String> revokedBlocks = new HashSet<>();
		private long lastCheckedStateRevision = -1;
		private long lastCheckedTick = -20;
	}
}
