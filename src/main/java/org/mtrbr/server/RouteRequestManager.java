package org.mtrbr.server;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtrbr.mixin.SidingAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Simulation-thread owner of RouteRequest, FCFS selection and authorization lifecycles. */
public final class RouteRequestManager {
	private static final Map<Simulator, State> STATES = new IdentityHashMap<>();
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
		current.vehicle = vehicle;
		current.path = path;
		current.head = head;
		current.tail = tail;
		current.sections = Set.copyOf(occupiedSections);
		current.observed = !path.isEmpty();
		if (path.isEmpty()) {
			return;
		}

		final List<PathSnapshot.FaceDistance> faceDistances = path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		for (final PathSnapshot.FaceDistance faceDistance : faceDistances) {
			if (faceDistance.distance() > current.lastHead && faceDistance.distance() <= head) {
				current.lastPassedSignalMillis = System.currentTimeMillis();
			}
		}
		current.lastHead = head;

		if (!path.matchesTopology(simulator)) {
			releaseAll(simulator, current);
			current.managed = true;
			if (current.request != null) {
				transition(current.request, RequestState.INVALID, "Path topology changed before vehicle movement");
			}
			return;
		}

		boolean inSiding = false;
		for (final PathSnapshot.PathSection section : path.getSections()) {
			if (head >= section.startDistance() && head <= section.endDistance()) {
				inSiding = section.isSiding();
				break;
			}
		}
		current.inSiding = inSiding;

		if (current.authorization != null && current.request != null && !current.request.getPathFingerprint().equals(path.getFingerprint())) {
			release(simulator, current);
			transition(current.request, RequestState.INVALID, "MTR regenerated immutablePath");
		}

		if (current.authorization != null) {
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
				final List<PathSnapshot.FaceDistance> allFaces = path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
				System.out.println("[MTRBR-NORANGE] vehicle=" + vehicle.getId()
						+ " head=" + String.format("%.1f", head)
						+ " pathTotal=" + String.format("%.1f", path.getTotalDistance())
						+ " faces=" + allFaces.size()
						+ " facesAhead=" + allFaces.stream().filter(fd -> fd.distance() > head).count());
			}
			current.managed = false;
			return;
		}

		if (current.request == null && head < range.triggerStart()) {
			current.managed = false;
			return;
		}
		current.managed = true;

		final boolean needsNewRequest = current.request == null
				|| current.request.getState() == RequestState.RELEASED
				|| current.request.getState() == RequestState.INVALID
				|| current.request.getState() == RequestState.REVOKED
				|| current.request.getState() == RequestState.CANCELED
				|| !current.request.getPathFingerprint().equals(path.getFingerprint())
				|| !current.controlFaceId.equals(range.faceId());

		if (needsNewRequest) {
			release(simulator, current);
			current.generation++;
			current.controlFaceId = range.faceId();
			current.controlDistance = range.controlDistance();
			// triggerDistance only decides when a Request is created. The authorized
			// route itself is generated from the complete immutablePath, except for a
			// siding departure where the route stops at the first section end so the
			// siding exit does not pre-lock the whole mainline.
			final double endDistance = inSiding ? path.getFirstSectionEndAfter(range.controlDistance()) : range.endDistance();
			if (endDistance <= current.controlDistance) {
				current.managed = false;
				return;
			}
			current.endDistance = endDistance;
			final List<String> sectionIds = path.getSectionIdsBetween(current.controlDistance, current.endDistance);
			final List<String> signalFaceIds = path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
					.filter(point -> point.distance() >= current.controlDistance && point.distance() <= current.endDistance)
					.map(point -> point.face().id())
					.toList();
			current.request = new RouteRequest(vehicle.getId(), path.getFingerprint(), current.generation, SectionStateManager.getCurrentTick(),
					Math.max(0, current.endDistance - head), sectionIds, signalFaceIds);
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
			return true;
		});
		state.vehicles.values().forEach(vehicle -> vehicle.observed = false);

		for (final VehicleState vehicle : state.vehicles.values()) {
			if (!vehicle.managed || vehicle.request == null) {
				continue;
			}
			if (!vehicle.path.matchesTopology(simulator)) {
				releaseAll(simulator, vehicle);
				transition(vehicle.request, RequestState.INVALID, "Path topology changed");
				continue;
			}
			if (vehicle.request.getState() == RequestState.REVOKED || vehicle.request.getState() == RequestState.CANCELED) {
				continue;
			}
			if (vehicle.authorization != null) {
				updateAuthorizedLifecycle(simulator, vehicle);
				extendAuthorization(simulator, vehicle);
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
			final Clearance clearance = clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.endDistance);
			vehicle.lastCheckedStateRevision = stateRevision;
			vehicle.lastCheckedTick = tick;
			transition(vehicle.request, clearance.sectionIds().isEmpty() ? RequestState.DENIED : RequestState.WAITING,
					clearance.sectionIds().isEmpty() ? "First section unavailable" : "Waiting for FCFS");
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
			final Clearance clearance = clearancePrefix(simulator, vehicle, vehicle.controlDistance, vehicle.endDistance);
			final List<String> authorizedSections = clearance.sectionIds();
			if (authorizedSections.isEmpty()) {
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
				continue;
			}
			if (!SectionStateManager.reserveSections(simulator, authorizedSections, request.getRequestId(), request.getVehicleId(), false)) {
				continue;
			}
			if (!SectionStateManager.lockSections(simulator, authorizedSections, request.getRequestId())) {
				SectionStateManager.releaseSections(simulator, authorizedSections, request.getRequestId());
				continue;
			}
			final Authorization authorization = new Authorization(request.getRequestId() + ":auth", request.getRequestId(), authorizedSections,
					vehicle.path.getNodeKeysBetween(vehicle.controlDistance, clearance.endDistance()), SectionStateManager.getTopologyRevision(simulator),
					++state.authorizationRevision, false);
			vehicle.authorization = authorization;
			vehicle.authorizationEndDistance = clearance.endDistance();
			transition(request, RequestState.AUTHORIZED, "FCFS progressive authorization");
		}
		debugVehicles(simulator, state);
		publishAuthorizations(simulator, state);
	}

	private static ControlRange findControlRange(Simulator simulator, PathSnapshot path, double head) {
		final List<ControlPoint> ahead = path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.map(point -> new ControlPoint(point.face(), point.distance()))
				.filter(point -> point.distance() > head)
				.toList();
		if (ahead.isEmpty()) {
			return null;
		}
		final double controlDistance = ahead.get(0).distance();
		final double stopDistance = path.getNextStoppingDistance(controlDistance);
		final double fourthControlDistance = ahead.size() > 3 ? ahead.get(3).distance() : path.getTotalDistance();
		// Request 窗口长度 = min(下一运营停车点距离, 前方约 4 个信号控制边界距离)。
		final double endDistance = Math.min(stopDistance, fourthControlDistance);
		if (endDistance <= controlDistance) {
			return null;
		}
		final List<String> signalFaceIds = ahead.stream()
				.filter(point -> point.distance() >= controlDistance && point.distance() <= endDistance)
				.map(point -> point.face().id())
				.toList();
		final double triggerLength = Math.min(Math.max(0, stopDistance - controlDistance), Math.max(0, fourthControlDistance - controlDistance));
		return new ControlRange(ahead.get(0).face().id(), controlDistance, endDistance, Math.max(0, controlDistance - triggerLength), signalFaceIds);
	}

	/** 沿 Request 区段向前，取到第一个不可用 Section 之前的所有 Section。 */
	private static Clearance clearancePrefix(Simulator simulator, VehicleState vehicle, double startDistance, double endDistance) {
		final List<String> authorizedRailIds = new ArrayList<>();
		double authorizedEnd = startDistance;
		final List<PathSnapshot.FaceDistance> faces = vehicle.path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		for (final PathSnapshot.SignalBlock block : vehicle.path.getSignalBlocksBetween(faces, startDistance, endDistance)) {
			final SectionCheck.Result check = SectionCheck.check(simulator, true, block.railIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false);
			if (!check.safe()) {
				break;
			}
			authorizedRailIds.addAll(block.railIds());
			authorizedEnd = block.endDistance();
		}
		return new Clearance(List.copyOf(authorizedRailIds), authorizedEnd);
	}

	/** 在已授权前缀之后继续尝试锁闭下一段空闲 Section，使 Authorization 随列车推进动态扩展。 */
	private static void extendAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null || vehicle.authorizationEndDistance >= vehicle.endDistance) {
			return;
		}
		final Clearance extension = clearancePrefix(simulator, vehicle, vehicle.authorizationEndDistance, vehicle.endDistance);
		if (extension.sectionIds().isEmpty()) {
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

		if (!SectionStateManager.reserveSections(simulator, extension.sectionIds(), vehicle.request.getRequestId(), vehicle.request.getVehicleId(), false)) {
			return;
		}
		if (!SectionStateManager.lockSections(simulator, extension.sectionIds(), vehicle.request.getRequestId())) {
			SectionStateManager.releaseSections(simulator, extension.sectionIds(), vehicle.request.getRequestId());
			return;
		}

		final Set<String> combinedSections = new java.util.LinkedHashSet<>(vehicle.authorization.getSectionIds());
		combinedSections.addAll(extension.sectionIds());
		final Set<String> combinedNodes = new java.util.LinkedHashSet<>(vehicle.authorization.getNodeKeys());
		combinedNodes.addAll(vehicle.path.getNodeKeysBetween(vehicle.authorizationEndDistance, extension.endDistance()));
		final Authorization extended = new Authorization(vehicle.request.getRequestId() + ":auth", vehicle.request.getRequestId(),
				List.copyOf(combinedSections), List.copyOf(combinedNodes), SectionStateManager.getTopologyRevision(simulator),
				state == null ? vehicle.authorization.getRevision() + 1 : ++state.authorizationRevision, false);
		vehicle.authorization = extended;
		vehicle.authorizationEndDistance = extension.endDistance();
	}

	private static void updateAuthorizedLifecycle(Simulator simulator, VehicleState vehicle) {
		if (vehicle.head >= vehicle.controlDistance) {
			transition(vehicle.request, RequestState.ACTIVE, "Entered authorized route");
		}
		if (vehicle.head >= vehicle.endDistance) {
			transition(vehicle.request, RequestState.PASSED, "Passed route end boundary");
		}
		// Section 逐条释放：车尾离开某个 Section 即释放该 Section 的 reserved/locked；
		// Request 只在完整进路走完后才 RELEASED。
		for (final PathSnapshot.PathSection section : vehicle.path.getSectionsBetween(vehicle.controlDistance, vehicle.authorizationEndDistance)) {
			if (vehicle.tail >= section.endDistance()) {
				SectionStateManager.releaseSections(simulator, List.of(section.sectionId()), vehicle.request.getRequestId());
			}
		}
		if (vehicle.tail >= vehicle.endDistance) {
			release(simulator, vehicle);
			transition(vehicle.request, RequestState.RELEASED, "Vehicle tail cleared route");
		}
	}

	private static void release(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization != null && vehicle.request != null) {
			SectionStateManager.releaseSections(simulator, vehicle.authorization.getSectionIds(), vehicle.request.getRequestId());
			vehicle.authorization = null;
		}
	}

	private static void releaseAll(Simulator simulator, VehicleState vehicle) {
		release(simulator, vehicle);
	}

	private static void transition(RouteRequest request, RequestState next, String reason) {
		if (request.getState() != next) {
			try {
				request.transitionTo(next, reason);
			} catch (IllegalStateException ignored) {
			}
		}
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
		if (vehicle.manualDrivingOverride && vehicle.vehicle.vehicleExtraData.getIsCurrentlyManual()) {
			return Double.NaN;
		}
		if (vehicle.request != null && (vehicle.request.getState() == RequestState.INVALID || vehicle.request.getState() == RequestState.REVOKED)) {
			return vehicle.head;
		}

		final double maxAuthorizedEnd = vehicle.authorization == null ? -1 : vehicle.authorizationEndDistance;
		final List<PathSnapshot.FaceDistance> faces = vehicle.path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		double collisionBoundary = Double.NaN;
		final Map<String, SectionStateManager.SectionSnapshot> sectionStates = SectionStateManager.getSections(simulator, vehicle.path.getSections().stream().map(PathSnapshot.PathSection::sectionId).toList());
		for (final PathSnapshot.SignalBlock block : vehicle.path.getSignalBlocksBetween(faces, vehicle.head, vehicle.path.getTotalDistance())) {
			boolean occupied = false;
			for (final String railId : block.railIds()) {
				final SectionStateManager.SectionSnapshot sectionState = sectionStates.get(railId);
				if (sectionState != null && sectionState.occupiedBy.stream().anyMatch(other -> other != vehicleId)) {
					occupied = true;
					break;
				}
			}
			if (occupied) {
				collisionBoundary = block.startDistance();
				break;
			}
		}

		double signalBoundary = Double.NaN;
		if (maxAuthorizedEnd < 0) {
			if (vehicle.request != null) {
				signalBoundary = vehicle.controlDistance;
			} else {
				for (final PathSnapshot.FaceDistance faceDistance : faces) {
					if (faceDistance.distance() > vehicle.head) {
						signalBoundary = faceDistance.distance();
						break;
					}
				}
			}
		} else {
			for (final PathSnapshot.FaceDistance faceDistance : faces) {
				if (faceDistance.distance() > maxAuthorizedEnd) {
					signalBoundary = faceDistance.distance();
					break;
				}
			}
		}

		if (Double.isNaN(collisionBoundary)) {
			return signalBoundary;
		}
		if (Double.isNaN(signalBoundary)) {
			return collisionBoundary;
		}
		return Math.min(signalBoundary, collisionBoundary);
	}

	/** Queues an OP-approved manual override on the simulator's own thread. */
	public static void setManualDrivingOverride(Simulator simulator, long vehicleId, boolean enabled) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null) {
				vehicle.manualDrivingOverride = enabled && vehicle.vehicle.vehicleExtraData.getIsCurrentlyManual();
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
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-priority vehicle=" + vehicleId + " priority=" + priority);
			}
		});
	}

	/** 人工批准：提升优先级并强制下一 tick 重新执行 SectionCheck。 */
	public static void approveWaiting(Simulator simulator, long vehicleId) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null && vehicle.request != null && vehicle.authorization == null) {
				vehicle.request.setManualPriority(100000);
				vehicle.lastCheckedStateRevision = -1;
				vehicle.lastCheckedTick = -20;
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-approve vehicle=" + vehicleId);
			}
		});
	}

	/** Revokes a not-yet-entered authorization. A live route cannot be revoked through this API. */
	public static void revokePendingAuthorization(Simulator simulator, long vehicleId) {
		simulator.run(() -> {
			final State state = STATES.get(simulator);
			final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
			if (vehicle != null && vehicle.authorization != null && vehicle.request.getState() == RequestState.AUTHORIZED) {
				release(simulator, vehicle);
				transition(vehicle.request, RequestState.REVOKED, "Manual dispatcher revocation before route entry");
				state.audit.add("tick=" + SectionStateManager.getCurrentTick() + " dispatcher-revoke vehicle=" + vehicleId);
			}
		});
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
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		return vehicle != null && vehicle.manualDrivingOverride && vehicle.vehicle != null
				&& vehicle.vehicle.vehicleExtraData.getIsCurrentlyManual() && vehicle.request != null
				&& vehicle.request.getState() != RequestState.INVALID && vehicle.request.getState() != RequestState.REVOKED;
	}

	public static List<AuthorizedPath> getAuthorizedPaths(Simulator simulator) {
		return AUTHORIZATION_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	/** Vehicle position snapshots used only for SignalFace -> Section ID mapping; not an occupancy source. */
	public static List<VehicleSnapshot> getVehicleSnapshots(Simulator simulator) {
		return VEHICLE_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	public static List<RequestSnapshot> getRequestSnapshots(Simulator simulator) {
		return REQUEST_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	public static List<String> getAudit(Simulator simulator) {
		return AUDIT_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	/** Clears all request/authorization state when the server stops. */
	public static void resetAll() {
		STATES.clear();
		AUTHORIZATION_SNAPSHOTS = Map.of();
		VEHICLE_SNAPSHOTS = Map.of();
		REQUEST_SNAPSHOTS = Map.of();
		AUDIT_SNAPSHOTS = Map.of();
	}

	private static void publishAuthorizations(Simulator simulator, State state) {
		final Map<Simulator, List<AuthorizedPath>> next = new IdentityHashMap<>(AUTHORIZATION_SNAPSHOTS);
		final List<AuthorizedPath> paths = new ArrayList<>();
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.authorization != null) {
				// 授权范围从“车头当前位置”开始，而不是固定从请求创建时的 controlDistance 开始；
				// 这样列车已经驶过的信号不会继续被判定为“已授权绿灯”。
				final double activeStart = Math.max(vehicle.controlDistance, vehicle.head);
				if (activeStart < vehicle.authorizationEndDistance) {
					paths.add(new AuthorizedPath(vehicle.path, activeStart, vehicle.authorizationEndDistance, vehicle.authorization.getAuthorizationId(), vehicle.authorization.getRevision()));
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
				.filter(vehicle -> vehicle.request != null)
				.map(vehicle -> new RequestSnapshot(vehicle.request.getVehicleId(), vehicle.request.getState(), vehicle.controlDistance, vehicle.endDistance, vehicle.authorizationEndDistance, vehicle.authorization != null,
						routeName(vehicle), routeDestination(vehicle)))
				.toList());
		REQUEST_SNAPSHOTS = Collections.unmodifiableMap(nextRequests);

		final Map<Simulator, List<String>> nextAudit = new IdentityHashMap<>(AUDIT_SNAPSHOTS);
		nextAudit.put(simulator, List.copyOf(state.audit));
		AUDIT_SNAPSHOTS = Collections.unmodifiableMap(nextAudit);
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

	public record AuthorizedPath(PathSnapshot path, double startDistance, double endDistance, String authorizationId, long revision) {
	}

	public record VehicleSnapshot(PathSnapshot path, double head, double tail) {
	}

	public record RequestSnapshot(long vehicleId, RequestState state, double controlDistance, double endDistance, double authorizationEndDistance, boolean authorized, String routeName, String destination) {
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
		final String station = vehicle.vehicle.vehicleExtraData.getThisStationName();
		return station == null ? "" : station;
	}

	private record ControlPoint(SignalFace face, double distance) {
	}

	private record ControlRange(String faceId, double controlDistance, double endDistance, double triggerStart, List<String> signalFaceIds) {
	}

	private record Clearance(List<String> sectionIds, double endDistance) {
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
		private double authorizationEndDistance;
		private String controlFaceId = "";
		private boolean inSiding;
		private double lastHead = -1;
		private long lastPassedSignalMillis;
		private long lastNoRangeDebugMillis;
		private long generation;
		private boolean observed;
		private boolean managed;
		private boolean manualDrivingOverride;
		private long lastCheckedStateRevision = -1;
		private long lastCheckedTick = -20;
	}
}
