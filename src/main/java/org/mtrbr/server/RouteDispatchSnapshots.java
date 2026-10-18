package org.mtrbr.server;

import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mtrbr.server.RouteRequestManager.ActivityAuthorization;
import org.mtrbr.server.RouteRequestManager.AuthorizedPath;
import org.mtrbr.server.RouteRequestManager.GeneratedProtection;
import org.mtrbr.server.RouteRequestManager.RequestSnapshot;
import org.mtrbr.server.RouteRequestManager.VehicleSnapshot;
import org.mtrbr.server.RouteRuntime.PhysicalBlockOccupancy;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.TurnbackPhaseFence;
import org.mtrbr.server.RouteRuntime.VehicleState;

/** Immutable dispatcher/signal projections and route display metadata; never grants movement authority. */
final class RouteDispatchSnapshots {
	private RouteDispatchSnapshots() {}

	private static volatile Map<Simulator, List<AuthorizedPath>> AUTHORIZATION_SNAPSHOTS = Map.of();

	private static volatile Map<Simulator, List<VehicleSnapshot>> VEHICLE_SNAPSHOTS = Map.of();

	private static volatile Map<Simulator, List<RequestSnapshot>> REQUEST_SNAPSHOTS = Map.of();

	private static volatile Map<Simulator, List<String>> AUDIT_SNAPSHOTS = Map.of();

	static List<AuthorizedPath> getAuthorizedPaths(Simulator simulator) {
		return AUTHORIZATION_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	/** Vehicle position snapshots used only for SignalFace -> Section ID mapping; not an occupancy source. */
	static List<VehicleSnapshot> getVehicleSnapshots(Simulator simulator) {
		return VEHICLE_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	/** Explicit protection regeneration only: compile observed directed protection boundaries into canonical data. */
	static Map<String, GeneratedProtection> getGeneratedProtectionBlocks(Simulator simulator, ServerAspectManager.FaceSnapshot topology) {
		final Map<String, GeneratedProtection> result = new java.util.LinkedHashMap<>();
		final Map<String, Double> boundaryDistances = new java.util.LinkedHashMap<>();
		for (final VehicleSnapshot snapshot : getVehicleSnapshots(simulator)) {
			final List<PathSnapshot.FaceTraversal> faces = snapshot.path().getFaceTraversals(simulator.dimension, topology).stream()
					.filter(PathSnapshot::isDirectionMatched).toList();
			for (final PathSnapshot.FaceTraversal first : faces) {
				final RouteProjection.Definition definition = RouteProjection.define(simulator, snapshot.path(), faces, first);
				final PathSnapshot.ProtectionBoundary boundary = definition.boundary();
				if (boundary.distance() <= first.distance() + 1.0E-6) continue;
				final String blockId = definition.blockDefinitionId();
				final List<String> rails = definition.sectionIds();
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

	/**
	 * Compiles each observed FaceTraversal occurrence independently. The key includes
	 * the immutable path fingerprint, so a repeated physical SignalFace cannot borrow
	 * another occurrence's boundary.
	 */
	static Map<String, GeneratedProtection> getGeneratedOccurrenceProtectionBlocks(Simulator simulator, ServerAspectManager.FaceSnapshot topology) {
		final Map<String, GeneratedProtection> result = new java.util.LinkedHashMap<>();
		for (final VehicleSnapshot snapshot : getVehicleSnapshots(simulator)) {
			final List<PathSnapshot.FaceTraversal> faces = snapshot.path().getFaceTraversals(simulator.dimension, topology).stream()
					.filter(PathSnapshot::isDirectionMatched).toList();
			for (final PathSnapshot.FaceTraversal first : faces) {
				final RouteProjection.Definition definition = RouteProjection.define(simulator, snapshot.path(), faces, first);
				final PathSnapshot.ProtectionBoundary boundary = definition.boundary();
				if (boundary.distance() <= first.distance() + 1.0E-6) continue;
				final String blockId = definition.blockDefinitionId();
				final List<String> rails = definition.sectionIds();
				if (rails.isEmpty()) continue;
				final String occurrenceKey = SignalBlockSavedData.occurrenceKey(snapshot.path().getFingerprint(), first.key());
				MtrbrDebugLog.event("MTRBR-OCCURRENCE-BLOCK-GENERATED", "pathFingerprint=" + snapshot.path().getFingerprint()
						+ " occurrenceFaceKey=" + first.key() + " entryFace=" + first.key()
						+ " boundaryFace=" + boundary.id() + " traversalIdentity=" + definition.traversals()
						+ " junctionMovementIds=" + definition.junctionMovementIds() + " blockDefinitionId=" + blockId
						+ " lookupKey=" + occurrenceKey);
				result.putIfAbsent(occurrenceKey, new GeneratedProtection(blockId, rails, boundary.id()));
			}
		}
		return Map.copyOf(result);
	}

	static List<RequestSnapshot> getRequestSnapshots(Simulator simulator) {
		return REQUEST_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	static List<String> getAudit(Simulator simulator) {
		return AUDIT_SNAPSHOTS.getOrDefault(simulator, List.of());
	}

	static void publishAuthorizations(Simulator simulator, State state) {
		final Map<Simulator, List<AuthorizedPath>> next = new IdentityHashMap<>(AUTHORIZATION_SNAPSHOTS);
		final List<AuthorizedPath> paths = new ArrayList<>();
		for (final VehicleState vehicle : state.vehicles.values()) {
			if (vehicle.authorization != null && vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()) {
				// 授权范围从“车头当前位置”开始，而不是固定从请求创建时的 controlDistance 开始；
				// 这样列车已经驶过的信号不会继续被判定为“已授权绿灯”。
				final ActivityAuthorization activity = vehicle.activityAuthorization;
				if (activity.startDistance() < activity.endDistance()) {
					final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, vehicle.head, "SIGNAL_PROJECTION");
					final List<PathSnapshot.FaceTraversal> faces = RouteTurnback.phaseFaces(simulator, vehicle, fence);
					final var shuntExit = ShuntSignalPolicy.exitAtHead(simulator.dimension, vehicle.path, faces, vehicle.head);
					final double displayStart = shuntExit == null ? activity.startDistance() : Math.min(activity.startDistance(), shuntExit.distance());
					final double firstVisibleFace = shuntExit == null ? vehicle.head : Math.min(vehicle.head, shuntExit.distance());
					final List<PathSnapshot.FaceTraversalKey> displayFaceKeys = faces.stream()
							.filter(face -> face.distance() >= firstVisibleFace - 1.0E-6 && face.distance() < vehicle.authorizationEndDistance - 1.0E-6)
							.filter(face -> vehicle.authorization.getFaceTraversalKeys().stream().anyMatch(key -> key.sameIdentity(face.key())))
							.map(PathSnapshot.FaceTraversal::key).toList();
					paths.add(new AuthorizedPath(vehicle.vehicle.getId(), vehicleCode(vehicle), vehicle.path, vehicle.authorization.getTraversals(), vehicle.authorization.getFaceTraversalKeys(), displayStart, activity.endDistance(), activity.blockIds(), displayFaceKeys, vehicle.authorization.getAuthorizationId(), vehicle.authorization.getRevision()));
				}
			}
		}
		next.put(simulator, List.copyOf(paths));
		AUTHORIZATION_SNAPSHOTS = Collections.unmodifiableMap(next);

		final Map<Simulator, List<VehicleSnapshot>> nextVehicles = new IdentityHashMap<>(VEHICLE_SNAPSHOTS);
		nextVehicles.put(simulator, state.vehicles.values().stream()
				.map(vehicle -> new VehicleSnapshot(vehicle.vehicle.getId(), vehicleCode(vehicle), vehicle.path, vehicle.head, vehicle.tail))
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
						vehicle.isAuthorizationEffective(), vehicle.overrideState == OverrideState.ONE_SHOT,
						currentSpeedKmh(vehicle),
						routeName(vehicle), routeDestination(vehicle), routeNextStation(vehicle),
						countOccupiedBlocks(simulator, vehicle),
						countAuthorizedBlocks(vehicle), countLockedBlocks(simulator, vehicle)))
				.toList());
		final long snapshotTick = SectionStateManager.getCurrentTick();
		state.vehicles.values().forEach(vehicle -> {
			vehicle.lastSnapshotHead = vehicle.head;
			vehicle.lastSnapshotTick = snapshotTick;
		});
		REQUEST_SNAPSHOTS = Collections.unmodifiableMap(nextRequests);

		final Map<Simulator, List<String>> nextAudit = new IdentityHashMap<>(AUDIT_SNAPSHOTS);
		nextAudit.put(simulator, state.audit.snapshot());
		AUDIT_SNAPSHOTS = Collections.unmodifiableMap(nextAudit);
	}

	/** Dispatcher Occ is physical occupation, independent of whether an Authorization still exists. */
	static int countOccupiedBlocks(Simulator simulator, VehicleState vehicle) {
		return (int) physicalBlockOccupancies(simulator, vehicle).values().stream()
				.filter(block -> RouteMath.rangesIntersect(vehicle.tail, vehicle.head, block.startDistance(), block.endDistance())).count();
	}

	static int countAuthorizedBlocks(VehicleState vehicle) {
		return vehicle.authorization == null ? 0 : (int) vehicle.authorization.getBlockAuthorizations().stream()
				.map(Authorization.BlockAuthorization::occurrenceId).distinct().count();
	}

	static int countLockedBlocks(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request == null) return 0;
		final String requestId = vehicle.request.getRequestId();
		final Set<String> lockedResources = new HashSet<>();
		for (final PhysicalBlockOccupancy block : physicalBlockOccupancies(simulator, vehicle).values()) {
			if (!RouteMath.rangesIntersect(vehicle.tail, vehicle.head, block.startDistance(), block.endDistance())) continue;
			if (SectionStateManager.areBlocksReservedAndLockedBy(simulator, List.of(block.occurrenceId()), requestId)
					|| SectionStateManager.areBlocksReservedAndLockedBy(simulator, List.of(block.blockId()), requestId)) {
				lockedResources.add("block:" + block.physicalKey());
			}
			if (JunctionStateManager.areResourcesReservedAndLockedBy(simulator, block.junctionResources(), requestId)) {
				for (final String resource : block.junctionResources()) lockedResources.add("junction:" + resource);
			}
		}
		return lockedResources.size();
	}

	private static Map<String, PhysicalBlockOccupancy> physicalBlockOccupancies(Simulator simulator, VehicleState vehicle) {
		final Map<String, PhysicalBlockOccupancy> blocks = new java.util.LinkedHashMap<>();
		if (vehicle.path == null || vehicle.path.isEmpty()) return blocks;
		if (vehicle.authorization != null) {
			for (final Authorization.BlockAuthorization block : vehicle.authorization.getBlockAuthorizations()) {
				final double blockEnd = Math.max(block.endDistance(), block.savedBlockEndDistance());
				final PhysicalBlockOccupancy occupancy = new PhysicalBlockOccupancy(block.blockId(), block.occurrenceId(),
					block.startDistance(), blockEnd, block.sectionIds(), block.junctionMovementIds(),
						block.entryFaceTraversalKey());
				blocks.putIfAbsent(occupancy.physicalKey(), occupancy);
			}
		}
		for (final RetainedProtection.Block pending : vehicle.retainedProtection.blocks()) {
			final PhysicalBlockOccupancy occupancy = new PhysicalBlockOccupancy(pending.blockId(), pending.occurrenceId(),
					pending.startDistance(), pending.endDistance(), pending.sectionIds(), pending.junctionResources(), pending.entryFaceKey());
			blocks.putIfAbsent(occupancy.physicalKey(), occupancy);
		}

		// When a malformed/failed Authorization has already vanished, project the
		// current path occurrence read-only so Occ still reflects the train body.
		final List<PathSnapshot.FaceTraversal> faces = RouteTurnback.phaseFaces(simulator, vehicle,
				RouteTurnback.turnbackPhaseFence(simulator, vehicle, vehicle.head, "DISPATCH_OCCUPANCY"));
		final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final PathSnapshot.FaceTraversal face : faces) {
			final String blockId = saved.getOccurrenceBlockId(vehicle.path.getFingerprint(), face.key());
			if (blockId.isBlank()) continue;
			final String boundaryId = saved.getBoundaryId(blockId);
			final PathSnapshot.ProtectionBoundary boundary = vehicle.path.getProtectionBoundary(face, faces, boundaryId);
			if (boundary == null || boundary.distance() <= face.distance() + 1.0E-6) continue;
			final List<PathSnapshot.PathTraversal> traversals = vehicle.path.getTraversalsBetween(face.distance(), boundary.distance());
			final List<String> sections = vehicle.path.getSectionIds(traversals);
			if (sections.isEmpty()) continue;
			final PhysicalBlockOccupancy occupancy = new PhysicalBlockOccupancy(blockId, blockId + "@" + face.pathTraversalIndex(),
					face.distance(), boundary.distance(), sections, JunctionStateManager.resourcesFor(simulator, traversals), face.key());
			blocks.putIfAbsent(occupancy.physicalKey(), occupancy);
		}
		return blocks;
	}

	static void updateSidingContext(Simulator simulator, VehicleState vehicle) {
		final long sidingId = vehicle.vehicle.vehicleExtraData.getSidingId();
		final Siding siding = simulator.sidingIdMap.get(sidingId);
		if (vehicle.inSiding || siding != null && vehicle.path != null && vehicle.path.isEmpty()) {
			vehicle.inSiding = true;
			vehicle.sidingDisplay = siding == null ? "" : siding.getDepotName();
		} else if (!vehicle.inSiding) {
			vehicle.sidingDisplay = "";
		}
	}

	static String vehicleCode(VehicleState vehicle) {
		return RouteVehicleCodes.getVehicleCode(vehicle.vehicle.getId());
	}

	static double currentSpeedKmh(VehicleState vehicle) {
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

	static String routeName(VehicleState vehicle) {
		final org.mtr.core.data.VehicleExtraData extraData = vehicle.vehicle.vehicleExtraData;
		String route = vehicle.inSiding
				? formatRoute(extraData.getNextRouteNumber(), extraData.getNextRouteName())
				: formatRoute(extraData.getThisRouteNumber(), extraData.getThisRouteName());
		if (route.isEmpty()) route = vehicle.inSiding
				? formatRoute(extraData.getThisRouteNumber(), extraData.getThisRouteName())
				: formatRoute(extraData.getNextRouteNumber(), extraData.getNextRouteName());
		if (route.isEmpty()) route = formatRoute(extraData.getPreviousRouteNumber(), extraData.getPreviousRouteName());
		if (!route.isEmpty()) {
			vehicle.lastRouteName = route;
			return route;
		}
		return RouteTurnback.isApproachingPlannedTurnback(vehicle) ? vehicle.lastRouteName : "";
	}

	static String formatRoute(String number, String name) {
		return (number == null || number.isEmpty() ? "" : number + " ") + (name == null ? "" : name);
	}

	static String routeDestination(VehicleState vehicle) {
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
		return RouteTurnback.isApproachingPlannedTurnback(vehicle) ? vehicle.lastRouteDestination : "";
	}

	static String routeNextStation(VehicleState vehicle) {
		final String station = vehicle.vehicle.vehicleExtraData.getNextStationName();
		if (station != null && !station.isEmpty()) {
			vehicle.lastNextStation = station;
			return station;
		}
		if (!vehicle.sidingDisplay.isEmpty()) {
			return vehicle.sidingDisplay;
		}
		return RouteTurnback.isApproachingPlannedTurnback(vehicle) ? vehicle.lastNextStation : "";
	}

	static void reset() {
		AUTHORIZATION_SNAPSHOTS = Map.of();
		VEHICLE_SNAPSHOTS = Map.of();
		REQUEST_SNAPSHOTS = Map.of();
		AUDIT_SNAPSHOTS = Map.of();
	}
}
