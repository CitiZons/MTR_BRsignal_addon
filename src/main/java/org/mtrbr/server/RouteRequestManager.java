package org.mtrbr.server;

import org.mtr.core.data.Vehicle;
import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtrbr.mixin.SidingAccess;
import org.mtrbr.mixin.VehicleNativeAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Simulation-thread owner of RouteRequest, FCFS selection and authorization lifecycles. */
public final class RouteRequestManager {
	private static final Map<Simulator, State> STATES = new java.util.IdentityHashMap<>();
	/** Published only after a complete simulation tick; safe for the Forge server thread to read. */
	private static volatile Map<Simulator, List<AuthorizedPath>> AUTHORIZATION_SNAPSHOTS = Map.of();
	/** 车辆实时位置快照，供服务端信号灯计算“区段占用立即变红”。 */
	private static volatile Map<Simulator, List<VehicleSnapshot>> VEHICLE_SNAPSHOTS = Map.of();

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
		// 记录车头本次越过哪些信号节点（用于停站后的出站信号 10 秒延迟开放）。
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
			if (current.pendingRequest != null) {
				transition(current.pendingRequest, RequestState.INVALID, "Path topology changed before vehicle movement");
			}
			return;
		}
		// 车库/折返段（siding）内的车辆出库也需要信号授权，因此仍然创建 Request；
		// 但授权范围只到出库段（一个 Section），不一次锁到 4 个信号/下一站，
		// 避免提前占用前方进路导致后续车辆全部 DENIED 并把车库堵死。
		boolean inSiding = false;
		for (final PathSnapshot.PathSection section : path.getSections()) {
			if (head >= section.startDistance() && head <= section.endDistance()) {
				inSiding = section.isSiding();
				break;
			}
		}
		current.inSiding = inSiding;
		if (current.authorization != null && !current.request.getPathFingerprint().equals(path.getFingerprint())) {
			release(simulator, current);
			transition(current.request, RequestState.INVALID, "MTR regenerated immutablePath");
		}
		if (current.authorization != null) {
			// 只要前方仍存在未被任何授权覆盖的信号（即下一灯非绿），就持续申请下一段，
			// 每秒兜底重试一次，让信号随列车前进逐级开放。
			maybeCreatePendingRequest(simulator, current);
			return;
		}
		if (current.request != null && current.request.getState() != RequestState.RELEASED && current.request.getState() != RequestState.INVALID && current.request.getState() != RequestState.CANCELED && current.request.getPathFingerprint().equals(path.getFingerprint())) {
			current.managed = true;
			current.request.setRemainingPathDistance(Math.max(0, current.controlDistance - head));
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
		if (current.request != null && current.authorization == null) {
			current.request.setRemainingPathDistance(Math.max(0, range.controlDistance() - head));
		}
		// A complete authorization remains attached to its original control face
		// until the tail clears the requested route. Passing that face must not
		// silently turn the next signal into a new request and release its locks.
		if (current.request == null || current.request.getState() == RequestState.RELEASED || current.request.getState() == RequestState.INVALID || current.request.getState() == RequestState.REVOKED || current.request.getState() == RequestState.CANCELED || !current.request.getPathFingerprint().equals(path.getFingerprint()) || !current.controlFaceId.equals(range.faceId())) {
			release(simulator, current);
			current.generation++;
			current.controlFaceId = range.faceId();
			current.controlDistance = range.controlDistance();
			final double endDistance = inSiding ? path.getFirstSectionEndAfter(range.controlDistance()) : range.endDistance();
			current.endDistance = endDistance;
			final List<String> sectionIds = path.getSectionIdsBetween(range.controlDistance(), endDistance);
			current.request = new RouteRequest(vehicle.getId(), path.getFingerprint(), current.generation, SectionStateManager.getCurrentTick(), Math.max(0, range.controlDistance() - head), sectionIds, range.signalFaceIds());
			transition(current.request, RequestState.APPROACHING, "Entered control approach");
			transition(current.request, RequestState.REQUESTED, "Complete route request created");
			transition(current.request, RequestState.CHECKING, "Section check scheduled");
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
				if (vehicle.pendingRequest != null) {
					transition(vehicle.pendingRequest, RequestState.INVALID, "Path topology changed");
				}
				continue;
			}
			if (vehicle.request.getState() == RequestState.REVOKED || vehicle.request.getState() == RequestState.CANCELED) {
				continue;
			}
			if (vehicle.authorization != null) {
				updateAuthorizedLifecycle(simulator, vehicle);
				processPendingRequest(simulator, vehicle);
				if (vehicle.head >= vehicle.endDistance && vehicle.pendingRequest != null) {
					promotePending(simulator, vehicle);
				}
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
			final SectionCheck.Result check = SectionCheck.check(simulator, true, vehicle.request.getSectionIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false);
			vehicle.lastCheckedStateRevision = stateRevision;
			vehicle.lastCheckedTick = tick;
			transition(vehicle.request, check.safe() ? RequestState.WAITING : RequestState.DENIED, check.safe() ? "Waiting for FCFS" : "Section check failed");
		}

		final List<RouteRequest> waiting = new ArrayList<>();
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.request != null && vehicle.request.getState() == RequestState.WAITING) {
				waiting.add(vehicle.request);
			}
			if (vehicle.pendingRequest != null && vehicle.pendingRequest.getState() == RequestState.WAITING) {
				waiting.add(vehicle.pendingRequest);
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
			final double requestStart = vehicle.request == request ? vehicle.controlDistance : vehicle.pendingControlDistance;
			final double requestEnd = vehicle.request == request ? vehicle.endDistance : vehicle.pendingEndDistance;
			// 出库信号按时刻表开放：车库车在预计发车前 10 秒内才授权出库请求。
			if (vehicle.inSiding && !isDepartureWindow(simulator, vehicle)) {
				continue;
			}
			// 部分授权：按路径顺序开放到最后一个空闲 Section；一旦遇到被占用/冲突的
			// Section 就截断，而不是整条进路全开或全拒。
			final List<PathSnapshot.PathSection> spans = vehicle.path.getSectionsBetween(requestStart, requestEnd);
			final List<String> authorizedSections = new ArrayList<>();
			double authorizedEnd = requestStart;
			boolean truncated = false;
			for (final PathSnapshot.PathSection span : spans) {
				final SectionStateManager.SectionSnapshot sectionState = SectionStateManager.getSections(simulator, List.of(span.sectionId())).get(span.sectionId());
				final boolean available = sectionState != null && sectionState.exists
						&& sectionState.lockedBy.stream().noneMatch(owner -> !owner.equals(request.getRequestId()))
						&& sectionState.reservedBy.stream().noneMatch(owner -> !owner.equals(request.getRequestId()))
						&& sectionState.occupiedBy.stream().noneMatch(vehicleId -> vehicleId != request.getVehicleId());
				if (!available) {
					truncated = true;
					break;
				}
				authorizedSections.add(span.sectionId());
				authorizedEnd = span.endDistance();
			}
			if (authorizedSections.isEmpty()) {
				continue; // 第一段就不可用：本段不开，等 SectionState 变化后重试。
			}
			final Set<String> requestNodes = new HashSet<>(vehicle.path.getNodeKeysBetween(requestStart, authorizedEnd));
			boolean nodeConflict = false;
			for (final VehicleState other : state.vehicles.values()) {
				if (other == vehicle) {
					continue;
				}
				if (other.authorization != null && !Collections.disjoint(other.authorization.getNodeKeys(), requestNodes)) {
					nodeConflict = true;
					break;
				}
				if (other.pendingAuthorization != null && !Collections.disjoint(other.pendingAuthorization.getNodeKeys(), requestNodes)) {
					nodeConflict = true;
					break;
				}
			}
			if (nodeConflict) {
				// 一个节点同时只能被一条进路开放：与其它车辆授权路径节点冲突时拒绝。
				continue;
			}
			if (!SectionStateManager.reserveSections(simulator, authorizedSections, request.getRequestId(), false)) {
				continue;
			}
			if (!SectionStateManager.lockSections(simulator, authorizedSections, request.getRequestId())) {
				SectionStateManager.releaseSections(simulator, authorizedSections, request.getRequestId());
				continue;
			}
			final Authorization authorization = new Authorization(request.getRequestId() + ":auth", request.getRequestId(), authorizedSections, vehicle.path.getNodeKeysBetween(requestStart, authorizedEnd), SectionStateManager.getTopologyRevision(simulator), ++state.authorizationRevision, false);
			if (vehicle.request == request) {
				vehicle.authorization = authorization;
				vehicle.endDistance = authorizedEnd;
			} else {
				vehicle.pendingAuthorization = authorization;
				vehicle.pendingEndDistance = authorizedEnd;
			}
			transition(request, RequestState.AUTHORIZED, truncated ? "Partial authorization up to last clear section" : "FCFS authorization");
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
		// Request 覆盖长度 = min(到下一运营停站距离, 向前约 4 架信号控制边界距离)。
		// 列车通过已授权区段后，由 maybeCreatePendingRequest 提前申请下一段，
		// 信号随车前进逐步开放，而不是把单次授权无限延伸到停站。
		final double fourthControlDistance = ahead.size() > 3 ? ahead.get(3).distance() : path.getTotalDistance();
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

	private static void updateAuthorizedLifecycle(Simulator simulator, VehicleState vehicle) {
		if (vehicle.head >= vehicle.controlDistance) {
			transition(vehicle.request, RequestState.ACTIVE, "Entered authorized route");
		}
		if (vehicle.head >= vehicle.endDistance) {
			transition(vehicle.request, RequestState.PASSED, "Passed route end boundary");
		}
		for (final PathSnapshot.PathSection section : vehicle.path.getSectionsBetween(vehicle.controlDistance, vehicle.endDistance)) {
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
		if (vehicle.authorization != null) {
			SectionStateManager.releaseSections(simulator, vehicle.authorization.getSectionIds(), vehicle.request.getRequestId());
			vehicle.authorization = null;
		}
	}

	private static void releaseAll(Simulator simulator, VehicleState vehicle) {
		release(simulator, vehicle);
		if (vehicle.pendingAuthorization != null) {
			SectionStateManager.releaseSections(simulator, vehicle.pendingAuthorization.getSectionIds(), vehicle.pendingRequest.getRequestId());
			vehicle.pendingAuthorization = null;
		}
	}

	/**
	 * 只要列车下一灯已离开当前授权范围（即不再是绿灯），就为下一段进路创建
	 * 预请求。预请求通过 SectionCheck/Dispatcher 后获得自己的 Authorization，
	 * 灯序链随之向前延伸；车头越过当前段终点时提升为活动请求。
	 */
	private static void maybeCreatePendingRequest(Simulator simulator, VehicleState vehicle) {
		if (vehicle.pendingRequest != null) {
			return;
		}
		final List<PathSnapshot.FaceDistance> faces = vehicle.path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension)).stream()
				.filter(point -> point.distance() > vehicle.head)
				.toList();
		if (faces.isEmpty()) {
			return;
		}
		final PathSnapshot.FaceDistance nextLight = faces.get(0);
		double maxAuthorizedEnd = -1;
		if (vehicle.authorization != null) {
			maxAuthorizedEnd = Math.max(maxAuthorizedEnd, vehicle.endDistance);
		}
		if (vehicle.pendingAuthorization != null) {
			maxAuthorizedEnd = Math.max(maxAuthorizedEnd, vehicle.pendingEndDistance);
		}
		PathSnapshot.FaceDistance firstUncovered = null;
		for (final PathSnapshot.FaceDistance faceDistance : faces) {
			if (faceDistance.distance() > maxAuthorizedEnd) {
				firstUncovered = faceDistance;
				break;
			}
		}
		if (firstUncovered == null) {
			return; // 授权覆盖到线路终点：下一个灯为绿，无需继续 request
		}
		// 判断“下一个灯是否绿灯”：下一个灯到红点之间至少隔 3 个已授权信号
		// （绿 -> 双黄 -> 黄 -> 红）。不足 3 个说明下一个灯是黄/双黄/红，必须持续 request。
		final PathSnapshot.FaceDistance redPoint = firstUncovered;
		final long coveredBetween = faces.stream()
				.filter(point -> point.distance() >= nextLight.distance() && point.distance() < redPoint.distance())
				.count();
		if (coveredBetween >= 3) {
			return;
		}
		final double nextControl = firstUncovered.distance();
		final double nextStop = vehicle.path.getNextStoppingDistance(nextControl);
		final List<PathSnapshot.FaceDistance> beyond = faces.stream()
				.filter(point -> point.distance() >= nextControl)
				.toList();
		final double nextFourth = beyond.size() > 3 ? beyond.get(3).distance() : vehicle.path.getTotalDistance();
		final double nextEnd = Math.min(nextStop, nextFourth);
		if (nextEnd <= nextControl) {
			return;
		}
		final List<String> sectionIds = vehicle.path.getSectionIdsBetween(nextControl, nextEnd);
		final List<String> faceIds = beyond.stream()
				.filter(point -> point.distance() >= nextControl && point.distance() <= nextEnd)
				.map(point -> point.face().id())
				.toList();
		vehicle.pendingRequest = new RouteRequest(vehicle.request.getVehicleId(), vehicle.path.getFingerprint(), vehicle.request.getGeneration() + 1, SectionStateManager.getCurrentTick(), Math.max(0, nextControl - vehicle.head), sectionIds, faceIds);
		vehicle.pendingControlFaceId = beyond.get(0).face().id();
		vehicle.pendingControlDistance = nextControl;
		vehicle.pendingEndDistance = nextEnd;
		transition(vehicle.pendingRequest, RequestState.APPROACHING, "Next control boundary left current authorization");
		transition(vehicle.pendingRequest, RequestState.REQUESTED, "Next route segment requested before current segment cleared");
		transition(vehicle.pendingRequest, RequestState.CHECKING, "Next route segment section check scheduled");
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
			return false; // 尚未生成发车时刻：出库信号不开
		}
		final int index = (int) vehicle.vehicle.getDepartureIndex();
		if (index < 0 || index >= departures.size()) {
			return false; // 尚未匹配到发车时刻：出库信号不开
		}
		final long departureTime = departures.getLong(index);
		if (departureTime <= 0) {
			return false;
		}
		return simulator.getCurrentMillis() >= departureTime - 10_000;
	}

	private static void processPendingRequest(Simulator simulator, VehicleState vehicle) {
		if (vehicle.pendingRequest == null || vehicle.pendingAuthorization != null) {
			return;
		}
		final long stateRevision = SectionStateManager.getStateRevision(simulator);
		final long tick = SectionStateManager.getCurrentTick();
		if (vehicle.pendingRequest.getState() == RequestState.DENIED && (vehicle.pendingLastCheckedStateRevision != stateRevision || tick - vehicle.pendingLastCheckedTick >= 20)) {
			transition(vehicle.pendingRequest, RequestState.CHECKING, "Relevant SectionState changed");
		}
		if (vehicle.pendingRequest.getState() == RequestState.DENIED || vehicle.pendingRequest.getState() == RequestState.WAITING && vehicle.pendingLastCheckedStateRevision == stateRevision) {
			return;
		}
		final SectionCheck.Result check = SectionCheck.check(simulator, true, vehicle.pendingRequest.getSectionIds(), vehicle.pendingRequest.getVehicleId(), vehicle.pendingRequest.getRequestId(), false);
		vehicle.pendingLastCheckedStateRevision = stateRevision;
		vehicle.pendingLastCheckedTick = tick;
		transition(vehicle.pendingRequest, check.safe() ? RequestState.WAITING : RequestState.DENIED, check.safe() ? "Waiting for FCFS" : "Section check failed");
	}

	private static void promotePending(Simulator simulator, VehicleState vehicle) {
		if (vehicle.pendingRequest == null) {
			return;
		}
		release(simulator, vehicle);
		transition(vehicle.request, RequestState.PASSED, "Passed route end boundary");
		transition(vehicle.request, RequestState.RELEASED, "Authorization superseded by next route segment");
		vehicle.request = vehicle.pendingRequest;
		vehicle.authorization = vehicle.pendingAuthorization;
		vehicle.controlFaceId = vehicle.pendingControlFaceId;
		vehicle.controlDistance = vehicle.pendingControlDistance;
		vehicle.endDistance = vehicle.pendingEndDistance;
		vehicle.pendingRequest = null;
		vehicle.pendingAuthorization = null;
	}

	private static void transition(RouteRequest request, RequestState next, String reason) {
		if (request.getState() != next) {
			try {
				request.transitionTo(next, reason);
			} catch (IllegalStateException ignored) {
			}
		}
	}

	/** Returns the control boundary at which an unauthorised managed vehicle must stop. */
	public static double getMovementBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null || !vehicle.managed || vehicle.authorization != null) {
			return Double.NaN;
		}
		if (vehicle.request == null) {
			return vehicle.path != null && !vehicle.path.matchesTopology(simulator) ? vehicle.head : Double.NaN;
		}
		if (vehicle.request.getState() == RequestState.INVALID || vehicle.request.getState() == RequestState.REVOKED) {
			return vehicle.head;
		}
		// Manual driving is the explicit operational override: it bypasses this
		// addon's red-signal gate only. It does not convert an unsafe request into
		// an authorization, and occupancy remains visible to automatic traffic.
		if (vehicle.manualDrivingOverride && vehicle.vehicle.vehicleExtraData.getIsCurrentlyManual()) {
			return Double.NaN;
		}
		return vehicle.controlDistance;
	}

	/**
	 * 渐进减速的红灯位置：沿车辆路径找“第一个不在任何有效授权覆盖内”的信号节点。
	 * 所有车辆（含已授权车）都以此为目标点按 MTR 制动曲线渐进减速；授权向前延伸时
	 * 该点后移，MTR 每 tick 重新评估，从而表现为“黄灯减速、红灯前停、可重新加速”。
	 * 无红点（授权覆盖到进路终点）或车辆尚未进入受控区域时返回 NaN（不限速）。
	 */
	public static double getStopBoundary(Simulator simulator, long vehicleId) {
		final State state = STATES.get(simulator);
		final VehicleState vehicle = state == null ? null : state.vehicles.get(vehicleId);
		if (vehicle == null || vehicle.path == null || vehicle.path.isEmpty()) {
			return Double.NaN;
		}
		// 人工驾驶 Override：确认处于手动驾驶的车辆不受本系统红灯限速/停车约束。
		if (vehicle.manualDrivingOverride && vehicle.vehicle.vehicleExtraData.getIsCurrentlyManual()) {
			return Double.NaN;
		}
		if (vehicle.request != null && (vehicle.request.getState() == RequestState.INVALID || vehicle.request.getState() == RequestState.REVOKED)) {
			return vehicle.head; // 立即停车
		}
		// 防撞边界：前方第一个被其他车辆占用的主线区段起点（车库段忽略，由 MTR 管理）。
		double collisionBoundary = Double.NaN;
		final List<String> sectionIds = vehicle.path.getSections().stream().map(PathSnapshot.PathSection::sectionId).toList();
		final Map<String, SectionStateManager.SectionSnapshot> sectionStates = SectionStateManager.getSections(simulator, sectionIds);
		for (final PathSnapshot.PathSection section : vehicle.path.getSections()) {
			if (section.isSiding() || section.endDistance() <= vehicle.head) {
				continue;
			}
			final SectionStateManager.SectionSnapshot sectionState = sectionStates.get(section.sectionId());
			if (sectionState != null && sectionState.occupiedBy.stream().anyMatch(other -> other != vehicleId)) {
				collisionBoundary = section.startDistance();
				break;
			}
		}
		double maxAuthorizedEnd = -1;
		if (vehicle.authorization != null) {
			maxAuthorizedEnd = Math.max(maxAuthorizedEnd, vehicle.endDistance);
		}
		if (vehicle.pendingAuthorization != null) {
			maxAuthorizedEnd = Math.max(maxAuthorizedEnd, vehicle.pendingEndDistance);
		}
		final List<PathSnapshot.FaceDistance> faces = vehicle.path.getFaceDistances(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		double signalBoundary = Double.NaN;
		if (maxAuthorizedEnd < 0) {
			// 未授权：红点固定为 request 的控制边界，不能随车头动态后移，
			// 否则车每越过一个信号红点就顺延一个，导致连续闯红灯。
			if (vehicle.request != null) {
				signalBoundary = vehicle.controlDistance;
			} else {
				// 尚无 request（尚未进入预告区）：以车头前方第一个同向信号为预告红点。
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

	/** 车辆实时位置快照（path/head/tail），供服务端灯序与占用判定使用。 */
	public static List<VehicleSnapshot> getVehicleSnapshots(Simulator simulator) {
		return VEHICLE_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	/** Clears all request/authorization state when the server stops. */
	public static void resetAll() {
		STATES.clear();
		AUTHORIZATION_SNAPSHOTS = Map.of();
		VEHICLE_SNAPSHOTS = Map.of();
	}

	private static void publishAuthorizations(Simulator simulator, State state) {
		final Map<Simulator, List<AuthorizedPath>> next = new java.util.IdentityHashMap<>(AUTHORIZATION_SNAPSHOTS);
		final List<AuthorizedPath> paths = new ArrayList<>();
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.authorization != null) {
				paths.add(new AuthorizedPath(vehicle.path, vehicle.controlDistance, vehicle.endDistance, vehicle.authorization.getAuthorizationId(), vehicle.authorization.getRevision()));
			}
			if (vehicle.pendingAuthorization != null) {
				paths.add(new AuthorizedPath(vehicle.path, vehicle.pendingControlDistance, vehicle.pendingEndDistance, vehicle.pendingAuthorization.getAuthorizationId(), vehicle.pendingAuthorization.getRevision()));
			}
		}
		next.put(simulator, List.copyOf(paths));
		AUTHORIZATION_SNAPSHOTS = Collections.unmodifiableMap(next);
		final Map<Simulator, List<VehicleSnapshot>> nextVehicles = new java.util.IdentityHashMap<>(VEHICLE_SNAPSHOTS);
		nextVehicles.put(simulator, state.vehicles.values().stream()
				.map(vehicle -> new VehicleSnapshot(vehicle.path, vehicle.head, vehicle.tail))
				.toList());
		VEHICLE_SNAPSHOTS = Collections.unmodifiableMap(nextVehicles);
	}

	private static long lastDebugMillis;

	/** 5 秒限流的服务端诊断：每辆车的请求/授权/预请求状态，用于定位信号不开放问题。 */
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
					+ " auth=" + (vehicle.authorization != null)
					+ " pending=" + (vehicle.pendingRequest == null ? "-" : vehicle.pendingRequest.getState())
					+ " pendingAuth=" + (vehicle.pendingAuthorization != null));
		}
	}

	public record AuthorizedPath(PathSnapshot path, double startDistance, double endDistance, String authorizationId, long revision) {
	}

	public record VehicleSnapshot(PathSnapshot path, double head, double tail) {
	}

	private record ControlPoint(SignalFace face, double distance) {
	}

	private record ControlRange(String faceId, double controlDistance, double endDistance, double triggerStart, List<String> signalFaceIds) {
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
		private RouteRequest pendingRequest;
		private Authorization pendingAuthorization;
		private String pendingControlFaceId = "";
		private double pendingControlDistance;
		private double pendingEndDistance;
		private Set<String> sections = Set.of();
		private double head;
		private double tail;
		private double controlDistance;
		private double endDistance;
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
		private long pendingLastCheckedStateRevision = -1;
		private long pendingLastCheckedTick = -20;
	}
}
