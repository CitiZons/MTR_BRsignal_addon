package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mtrbr.server.RouteRequestManager.ActivityAuthorization;
import org.mtrbr.server.RouteRequestManager.DispatchResult;
import org.mtrbr.server.RouteRuntime.Clearance;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.TurnbackPhaseFence;
import org.mtrbr.server.RouteRuntime.VehicleState;
import static org.mtrbr.server.RetainedProtection.blockLockIds;
import static org.mtrbr.server.RouteRuntime.STATES;

/** Authorization acquisition, extension, activity refresh and exact-owner resource retirement. */
final class RouteAuthorizationLifecycle {
	private RouteAuthorizationLifecycle() {}

	static void resetRouteCycle(Simulator simulator, VehicleState vehicle) {
		// A new timetable cycle is not proof that the train has cleared the interlocking.
		invalidateAuthorization(simulator, vehicle, ReleaseReason.INVALID, RequestState.INVALID);
		vehicle.turnbackIntent = null;
		vehicle.traversalContext = null;
		vehicle.lastValidActivity = null;
		vehicle.lastSafeBoundary = vehicle.head;
		vehicle.clearFixedGate();
		vehicle.activityAuthorization = null;
		vehicle.authorizationEndDistance = Double.NaN;
		vehicle.controlFaceId = "";
		vehicle.controlDistance = 0;
		vehicle.endDistance = 0;
		vehicle.authorizationLookaheadEndDistance = 0;
	}

	static void retainPendingOwners(VehicleState vehicle, Map<String, Set<String>> sections,
			Map<String, Set<String>> blocks, Map<String, Set<String>> junctions) {
		vehicle.retainedProtection.retainOwners(sections, blocks, junctions);
	}

	/** All calls run on the simulator thread before the next arbitration pass. No unlock/relock gap. */
	static void adoptRetainedResources(Simulator simulator, VehicleState vehicle, RouteRequest previous, RouteRequest next) {
		if (previous == null) return;
		if (previous.getVehicleId() != next.getVehicleId()) throw new IllegalArgumentException("Resource handover across trains");
		final String from = previous.getRequestId(), to = next.getRequestId();
		SectionStateManager.transferRequestOwner(simulator, from, to);
		JunctionStateManager.transferRequestOwner(simulator, from, to, next.getVehicleId());
		CapacityLeaseManager.transferRequestOwner(simulator, from, to, next.getVehicleId());
		vehicle.retainedProtection.transferOwner(from, to);
		MtrbrDebugLog.event("MTRBR-RESOURCE-HANDOVER", "vehicle=" + next.getVehicleId() + " oldOwner=" + from + " newOwner=" + to);
	}

	static void releaseTurnbackWindow(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) return;
		final String requestId = vehicle.request.getRequestId();
		final Set<String> pendingSections = new HashSet<>();
		for (final Authorization.BlockAuthorization block : vehicle.authorization.getBlockAuthorizations()) {
			if (isBlockPhysicallyOccupied(vehicle, block)) pendingSections.addAll(block.sectionIds());
		}
		pendingSections.addAll(vehicle.sections);
		pendingSections.addAll(vehicle.retainedProtection.blockSections());
		final Set<String> immediateSections = new HashSet<>(vehicle.authorization.getSectionIds());
		immediateSections.removeAll(pendingSections);
		SectionStateManager.releaseSections(simulator, immediateSections, requestId);
		vehicle.retainedProtection.retainSections(pendingSections, requestId, ReleaseReason.TURNBACK);
		final List<String> clearedBlockIds = new ArrayList<>();
		final List<String> clearedJunctionResources = new ArrayList<>();
		for (final Authorization.BlockAuthorization block : vehicle.authorization.getBlockAuthorizations()) {
			if (isBlockPhysicallyOccupied(vehicle, block)) {
				registerPendingRelease(simulator, vehicle, block, ReleaseReason.TURNBACK);
			} else {
				clearedBlockIds.addAll(blockLockIds(List.of(block)));
				clearedJunctionResources.addAll(block.junctionMovementIds());
				RouteDiagnostics.logTurnbackHandoff(vehicle, RetainedProtection.Block.from(vehicle.request.getRequestId(), block), "RELEASE_NOW", "NO_PHYSICAL_SECTION_INTERSECTION");
			}
		}
		SectionStateManager.releaseBlocks(simulator, vehicle.retainedProtection.releasableBlockLockIds(clearedBlockIds, List.of()), requestId);
		JunctionStateManager.release(simulator, vehicle.retainedProtection.releasableJunctionResources(clearedJunctionResources, List.of()), requestId);
		vehicle.authorization = null;
		vehicle.activityAuthorization = null;
		vehicle.authorizationRetryPending = true;
	}

	/** Acquires a checked prefix as one transaction; Authorization changes only after this succeeds. */
	static boolean reserveAndLock(Simulator simulator, VehicleState vehicle, Clearance clearance) {
		final String requestId = vehicle.request.getRequestId();
		final List<String> blockLockIds = blockLockIds(clearance.blockAuthorizations());
		final List<String> junctionResources = clearance.blockAuthorizations().stream()
				.flatMap(block -> block.junctionMovementIds().stream()).distinct().toList();
		final List<String> zoneIds = CapacityLeaseManager.getZoneIds(simulator, clearance.traversals(),
				SignalBlockSavedData.getSnapshot(simulator.dimension));
		if (!validateAuthorizationResources(simulator, vehicle, clearance, blockLockIds, junctionResources, zoneIds)) {
			return false;
		}
		return new AuthorizationResources(requestId, vehicle.request.getVehicleId(), clearance.sectionIds(),
				blockLockIds, junctionResources, zoneIds).reserveAndLock(simulator);
	}

	/** Stage B: read-only resource validation. No reservation or lock is mutated here. */
	static boolean validateAuthorizationResources(Simulator simulator, VehicleState vehicle, Clearance clearance,
			List<String> blockLockIds, List<String> junctionResources, List<String> zoneIds) {
		final String requestId = vehicle.request.getRequestId();
		if (!SectionStateManager.areBlocksAvailable(simulator, blockLockIds, requestId)) {
			MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=BLOCK vehicle=" + vehicle.request.getVehicleId()
					+ " blockedBy=<block-state> resource=" + blockLockIds
					+ " range=" + RouteMath.fmt(vehicle.controlDistance) + ".." + RouteMath.fmt(clearance.endDistance()));
			return false;
		}
		if (!SectionStateManager.areSectionsAvailable(simulator, clearance.sectionIds(), requestId, vehicle.request.getVehicleId(), false)) {
			MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=SECTION vehicle=" + vehicle.request.getVehicleId()
					+ " blockedBy=<section-state> resource=" + clearance.sectionIds()
					+ " range=" + RouteMath.fmt(vehicle.controlDistance) + ".." + RouteMath.fmt(clearance.endDistance()));
			return false;
		}
		if (!CapacityLeaseManager.areZonesAvailable(simulator, zoneIds, requestId)) {
			MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=SINGLE_LINE_ZONE vehicle=" + vehicle.request.getVehicleId()
					+ " resource=" + zoneIds + " range=" + RouteMath.fmt(vehicle.controlDistance) + ".." + RouteMath.fmt(clearance.endDistance()));
			return false;
		}
		final List<String> junctionOwners = JunctionStateManager.conflictOwners(simulator, junctionResources, requestId);
		if (!junctionOwners.isEmpty()) {
			MtrbrDebugLog.event("MTRBR-AUTH-CONFLICT", "type=JUNCTION_MOVEMENT vehicle=" + vehicle.request.getVehicleId()
					+ " blockedBy=" + junctionOwners + " resource=" + junctionResources
					+ " range=" + RouteMath.fmt(vehicle.controlDistance) + ".." + RouteMath.fmt(clearance.endDistance()));
			MtrbrDebugLog.event("MTRBR-DEADLOCK", "vehicle=" + vehicle.request.getVehicleId()
					+ " waitingResource=" + junctionResources + " ownerVehicle=" + junctionOwners
					+ " heldResources=<tracked-by-owner>");
			return false;
		}
		return true;
	}

	static Authorization createAuthorization(Simulator simulator, State state, VehicleState vehicle,
			List<Authorization.BlockAuthorization> blocks) {
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final Authorization.BlockAuthorization block : blocks) {
			RouteDiagnostics.auditBlockProjection(simulator, vehicle, block, savedBlocks);
		}
		final double start = blocks.stream().mapToDouble(Authorization.BlockAuthorization::startDistance).min().orElse(vehicle.head);
		final double end = blocks.stream().mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(start);
		return new Authorization(vehicle.request.getRequestId() + ":auth", vehicle.request.getRequestId(), blocks,
				vehicle.path.getPathNodesBetween(start, end), SectionStateManager.getTopologyRevision(simulator),
				state == null ? (vehicle.authorization == null ? 0 : vehicle.authorization.getRevision() + 1) : ++state.authorizationRevision);
	}

	/** 在已授权前缀之后继续尝试锁闭下一段空闲 Section，使 Authorization 随列车推进动态扩展。 */
	static void extendAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) {
			return;
		}
		refreshAuthorizationLookahead(simulator, vehicle);
		if (vehicle.authorizationEndDistance >= vehicle.authorizationLookaheadEndDistance - 1.0E-6) {
			RouteDiagnostics.logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, vehicle.authorizationLookaheadEndDistance, "<none>", "already extended");
			RouteDiagnostics.logAuthorizationExtendState(vehicle, "WAIT");
			return;
		}
		final double extensionStart = Math.max(vehicle.head, vehicle.authorizationEndDistance);
		Clearance extension = RouteAuthorizationPlanner.continueSavedBlockPrefix(simulator, vehicle, vehicle.authorizationLookaheadEndDistance);
		if (extension == null) {
			extension = RouteAuthorizationPlanner.firstBlock(RouteAuthorizationPlanner.clearancePrefix(simulator, vehicle, extensionStart, vehicle.authorizationLookaheadEndDistance));
		}
		if (extension.sectionIds().isEmpty() || extension.traversals().isEmpty()) {
			RouteDiagnostics.logAuthorizationExtendFail(vehicle, extensionStart, vehicle.authorizationLookaheadEndDistance, "<none>", "no next block");
			RouteDiagnostics.logAuthorizationExtendState(vehicle, "WAIT");
			return;
		}
		if (isAuthorizationExtensionAlreadyCovered(vehicle.authorization, extension)) {
			MtrbrDebugLog.event("MTRBR-AUTH-EXTEND-SKIP", "vehicle=" + vehicle.vehicle.getId()
					+ " request=" + vehicle.request.getRequestId()
					+ " block=" + extension.blockAuthorizations().get(0).blockId()
					+ " end=" + RouteMath.fmt(extension.endDistance())
					+ " reason=ALREADY_AUTHORIZED_OCCURRENCE");
			RouteDiagnostics.logAuthorizationExtendState(vehicle, "WAIT");
			return;
		}

		final State state = STATES.get(simulator);
		if (!reserveAndLock(simulator, vehicle, extension)) {
			RouteDiagnostics.logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, vehicle.authorizationLookaheadEndDistance,
					extension.blockIds().isEmpty() ? "<unknown>" : extension.blockIds().get(0), "fcfs denied");
			RouteDiagnostics.logAuthorizationExtendState(vehicle, "WAIT");
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
		final Authorization extended = createAuthorization(simulator, state, vehicle, combinedBlockAuthorizations);
			for (final String blockId : extension.blockIds()) {
				final String audit = "vehicle=" + vehicle.request.getVehicleId() + " blockId=" + blockId + " source=SAVED_DATA";
				MtrbrDebugLog.event("AUTH-BLOCK", audit);
			}
		vehicle.authorization = extended;
		vehicle.clearFixedGate();
		vehicle.lastAuthorizationId = extended.getAuthorizationId();
		vehicle.authorizationEndDistance = extension.endDistance();
		// The continuation may have repaired a prefix crossed in this tick. Refresh
		// the existing Activity projection before signal publication observes it.
		refreshActivityAuthorization(simulator, vehicle);
		RouteDiagnostics.auditAuthorizationOverextension(simulator, vehicle, vehicle.path.getNextTurnbackWindow(vehicle.head), vehicle.authorizationLookaheadEndDistance);
		RouteDiagnostics.logAuthorizationEnd(vehicle, "EXTEND", extension.endDistance());
	}

	/** An extension is a delta. Reapplying an already-held path occurrence must
	 * not grow Authorization or refresh its resource ownership indefinitely. */
	static boolean isAuthorizationExtensionAlreadyCovered(Authorization authorization, Clearance extension) {
		if (authorization == null) return false;
		for (final Authorization.BlockAuthorization candidate : extension.blockAuthorizations()) {
			final boolean covered = authorization.getBlockAuthorizations().stream().anyMatch(existing ->
					existing.completeSavedBlock() == candidate.completeSavedBlock()
						&& existing.blockId().equals(candidate.blockId())
						&& existing.entryFaceTraversalKey().sameIdentity(candidate.entryFaceTraversalKey())
						&& existing.startDistance() <= candidate.startDistance() + 1.0E-6
						&& existing.endDistance() >= candidate.endDistance() - 1.0E-6);
			if (!covered) return false;
		}
		return !extension.blockAuthorizations().isEmpty();
	}

	/** Recompute the moving authorization preview from the current vehicle position. */
	static void refreshAuthorizationLookahead(Simulator simulator, VehicleState vehicle) {
		trimAuthorizationPastOperationalBoundary(simulator, vehicle);
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, vehicle.head, "AUTHORIZATION_EXTENSION");
		final List<PathSnapshot.FaceTraversal> ahead = RouteTurnback.phaseFaces(simulator, vehicle, fence).stream()
				.filter(face -> face.distance() > vehicle.head + 1.0E-6)
				.toList();
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(vehicle.head);
		final double stopBoundary = turnback.stopDistance();
		final double turnbackBoundary = fence.fenceDistance();
		final double fiveSignalBoundary = ahead.size() > 4 ? ahead.get(4).distance() : vehicle.path.getTotalDistance();
		final double shuntBoundary = ShuntSignalPolicy.boundary(simulator.dimension, vehicle.path, RouteTurnback.phaseFaces(simulator, vehicle, fence), vehicle.head);
		final double recomputedEnd = Math.min(shuntBoundary, Math.min(stopBoundary, Math.min(turnbackBoundary, fiveSignalBoundary)));
		vehicle.authorizationLookaheadEndDistance = Math.min(vehicle.endDistance, Math.max(vehicle.head, recomputedEnd));
		MtrbrDebugLog.event("MTRBR-AUTH-WINDOW", "vehicle=" + vehicle.vehicle.getId()
				+ " headDistance=" + RouteMath.fmt(vehicle.head) + " stopBoundary=" + RouteMath.fmt(stopBoundary)
				+ " turnbackBoundary=" + RouteMath.fmt(turnbackBoundary) + " signalBoundary=" + RouteMath.fmt(fiveSignalBoundary)
				+ " finalAuthorizationEnd=" + RouteMath.fmt(vehicle.authorizationLookaheadEndDistance));
		RouteDiagnostics.auditAuthorizationOverextension(simulator, vehicle, turnback, vehicle.authorizationLookaheadEndDistance);
		RouteDiagnostics.logAuthorizationLookahead(vehicle, turnback, vehicle.authorizationLookaheadEndDistance);
	}

	/**
	 * Once the vehicle head has reached a scheduled platform boundary, retain the
	 * inbound Block containing that boundary but release any pre-authorized
	 * departure Blocks beyond it. This is a resource-boundary correction only;
	 * it does not add a vehicle state or alter signal Aspect calculation.
	 */
	static void trimAuthorizationPastOperationalBoundary(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) return;
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(vehicle.head - 1.0E-6);
		final double operationalBoundary = !turnback.requiresTurnback() && vehicle.head >= turnback.stopDistance() - 1.0E-6
				? turnback.stopDistance() : Double.POSITIVE_INFINITY;
		final double shuntBoundary = ShuntSignalPolicy.boundary(simulator.dimension, vehicle.path,
				RouteTurnback.phaseFaces(simulator, vehicle, RouteTurnback.turnbackPhaseFence(simulator, vehicle, vehicle.head, "SHUNT_BOUNDARY")), vehicle.head);
		final double boundary = Math.min(operationalBoundary, shuntBoundary);
		if (!Double.isFinite(boundary)) return;
		final List<Authorization.BlockAuthorization> active = vehicle.authorization.getBlockAuthorizations();
		final List<Authorization.BlockAuthorization> retained = active.stream()
				.filter(block -> block.startDistance() < boundary - 1.0E-6)
				.toList();
		if (retained.size() == active.size()) return;
		final List<Authorization.BlockAuthorization> removed = active.stream()
				.filter(block -> block.startDistance() >= boundary - 1.0E-6)
				.toList();
		final List<Authorization.BlockAuthorization> cleared = removed.stream()
				.filter(block -> !isBlockPhysicallyOccupied(vehicle, block))
				.toList();
		final List<Authorization.BlockAuthorization> pending = removed.stream()
				.filter(block -> isBlockPhysicallyOccupied(vehicle, block))
				.toList();
		final String requestId = vehicle.request.getRequestId();
		for (final Authorization.BlockAuthorization block : pending) {
			registerPendingRelease(simulator, vehicle, block, ReleaseReason.INVALID);
		}
		SectionStateManager.releaseBlocks(simulator, vehicle.retainedProtection.releasableBlockLockIds(blockLockIds(cleared), retained), requestId);
		final Set<String> retainedSections = new HashSet<>();
		for (final Authorization.BlockAuthorization block : retained) retainedSections.addAll(block.sectionIds());
		for (final Authorization.BlockAuthorization block : pending) retainedSections.addAll(block.sectionIds());
		retainedSections.addAll(vehicle.sections);
		retainedSections.addAll(vehicle.retainedProtection.blockSections());
		final Set<String> removedSections = new HashSet<>();
		for (final Authorization.BlockAuthorization block : removed) removedSections.addAll(block.sectionIds());
		removedSections.removeAll(retainedSections);
		SectionStateManager.releaseSections(simulator, removedSections, requestId);
		final List<String> removedJunctionResources = cleared.stream()
				.flatMap(block -> block.junctionMovementIds().stream()).toList();
		JunctionStateManager.release(simulator, vehicle.retainedProtection.releasableJunctionResources(removedJunctionResources, retained), requestId);
		vehicle.authorization = retained.isEmpty() ? null : createAuthorization(simulator, STATES.get(simulator), vehicle, retained);
		if (vehicle.authorization == null) {
			vehicle.authorizationRetryPending = true;
			enterAuthorizationRecovery(vehicle);
		}
		vehicle.authorizationEndDistance = retained.stream()
				.mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(vehicle.head);
		MtrbrDebugLog.event("AUTH-BOUNDARY-TRIM", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + requestId + " operationalBoundary=" + RouteMath.fmt(boundary)
				+ " releasedBlocks=" + cleared.stream().map(Authorization.BlockAuthorization::blockId).toList()
				+ " pendingBlocks=" + pending.stream().map(Authorization.BlockAuthorization::blockId).toList()
				+ " authorizationEnd=" + RouteMath.fmt(vehicle.authorizationEndDistance));
	}

	/** Projects the cumulative Authorization onto the current operational window. */
	static void refreshActivityAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.path == null) {
			if (vehicle.request != null && !isTerminal(vehicle.request.getState()) && vehicle.path != null
					&& vehicle.lastValidActivity != null && canReuseLastActivity(simulator, vehicle)) {
				vehicle.activityAuthorization = vehicle.lastValidActivity;
				vehicle.activityFallbackTicks++;
				MtrbrDebugLog.event("MTRBR-AUTH-RECOVERY", "vehicle=" + vehicle.vehicle.getId()
						+ " request=" + vehicle.request.getRequestId() + " reason=AUTHORIZATION_REFRESH_PENDING"
						+ " boundary=" + RouteMath.fmt(Math.max(vehicle.head, vehicle.lastValidActivity.endDistance())));
			} else if (vehicle.request == null || isTerminal(vehicle.request.getState()) || vehicle.path == null) {
				vehicle.activityAuthorization = null;
				vehicle.lastValidActivity = null;
			}
			RouteDiagnostics.logAuthorizationLifecycle(simulator, vehicle, "ACTIVITY_REFRESH_MISSING_AUTH");
			return;
		}
		final List<Authorization.BlockAuthorization> active = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> vehicle.tail < block.endDistance() - 1.0E-6)
				.toList();
		final double activeStart = vehicle.head;
		final double activeEnd = active.stream().mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(activeStart);
		final List<String> activeBlocks = active.stream().map(Authorization.BlockAuthorization::blockId).toList();
		// Face keys remain a display projection of the active directed Blocks.
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, vehicle.head, "ACTIVITY");
		final List<PathSnapshot.FaceTraversal> phaseFaces = RouteTurnback.phaseFaces(simulator, vehicle, fence);
		final List<PathSnapshot.FaceTraversalKey> activeFaceKeys = active.stream()
				.flatMap(block -> block.faceTraversalKeys().stream())
				.filter(key -> phaseFaces.stream()
						.anyMatch(face -> face.key().sameIdentity(key)))
				.distinct().toList();
		for (final Authorization.BlockAuthorization block : active) {
			RouteDiagnostics.auditBlockProjection(simulator, vehicle, block, SignalBlockSavedData.getSnapshot(simulator.dimension));
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
			if (vehicle.turnbackHandoff.awaitingActivity()) {
				RouteTurnback.logTurnbackActivity(vehicle, "<pending>", RouteDiagnostics.describeActivity(refreshed), "NEW_DIRECTION_ACTIVITY_READY",
						"RESUME_NORMAL_GATE", "AUTHORIZATION_AND_ACTIVITY_REFRESHED");
				vehicle.turnbackHandoff.activityReady();
				vehicle.turnbackIntent = null;
			}
		} else if (canReuseLastActivity(simulator, vehicle) && vehicle.head < activeEnd - 1.0E-6 && vehicle.activityFallbackTicks < 10) {
			vehicle.activityFallbackTicks++;
			vehicle.activityAuthorization = vehicle.lastValidActivity;
			MtrbrDebugLog.event("ACTIVITY", "fallback used vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId() + " reason=TRANSIENT_REFRESH_FAILURE ticks=" + vehicle.activityFallbackTicks);
		} else if (vehicle.lastValidActivity != null && canReuseLastActivity(simulator, vehicle)) {
			vehicle.activityAuthorization = vehicle.lastValidActivity;
			vehicle.activityFallbackTicks++;
			MtrbrDebugLog.event("MTRBR-AUTH-STALE", "vehicle=" + vehicle.vehicle.getId() + " request=" + vehicle.authorization.getRequestId()
					+ " headDistance=" + RouteMath.fmt(vehicle.head) + " authorizationEnd=" + RouteMath.fmt(vehicle.authorizationEndDistance)
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
		}
		RouteDiagnostics.debugActivity(simulator, vehicle, phaseFaces);
	}

	static boolean isTerminal(RequestState state) {
		return state == RequestState.PASSED || state == RequestState.RELEASED || state == RequestState.CANCELED
				|| state == RequestState.INVALID || state == RequestState.REVOKED;
	}

	static void enterAuthorizationRecovery(VehicleState vehicle) {
		if (vehicle.request == null || isTerminal(vehicle.request.getState())) return;
		if (vehicle.request.getState() == RequestState.AUTHORIZED) {
			vehicle.request.transitionIfChanged(RequestState.ACTIVE, "Authorization recovery started");
		}
	}

	static boolean canReuseLastActivity(Simulator simulator, VehicleState vehicle) {
		if (vehicle.lastValidActivity == null || !vehicle.lastValidActivity.valid() || vehicle.request == null || vehicle.path == null) return false;
		if (vehicle.head >= vehicle.lastValidActivity.endDistance() - 1.0E-6) return false;
		if (!vehicle.request.getRequestId().equals(vehicle.lastValidActivityRequestId) || !vehicle.path.getFingerprint().equals(vehicle.lastValidActivityPathFingerprint)) return false;
		if (vehicle.authorization != null && vehicle.authorization.getTopologyRevision() != vehicle.lastValidActivityTopologyRevision) return false;
		if (SectionStateManager.getTopologyRevision(simulator) != vehicle.lastValidActivityTopologyRevision) return false;
		final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
		return vehicle.lastValidActivity.blockIds().stream().allMatch(saved.blockRails()::containsKey);
	}

	static String activityFailureReason(Simulator simulator, VehicleState vehicle, List<String> activeBlocks, List<PathSnapshot.FaceTraversalKey> activeFaceKeys) {
		if (vehicle.authorization == null) return "AUTHORIZATION_MISSING";
		if (SectionStateManager.getTopologyRevision(simulator) != vehicle.authorization.getTopologyRevision()) return "TOPOLOGY_REVISION_CHANGED";
		if (activeFaceKeys.isEmpty()) return "AUTHORIZED_FACE_KEYS_EMPTY";
		if (activeBlocks.isEmpty()) return "BLOCK_IDENTITY_MISMATCH";
		return "ACTIVE_WINDOW_EMPTY";
	}

	/** Releases an authorization prefix only after the vehicle tail clears each Block boundary. */
	static void releaseAuthorizationPastHead(Simulator simulator, VehicleState vehicle) {
		if (vehicle.authorization == null || vehicle.request == null) return;
		final double epsilon = 1.0E-6;
		final List<Authorization.BlockAuthorization> cleared = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> vehicle.tail >= block.endDistance() - epsilon && !isBlockPhysicallyOccupied(vehicle, block)).toList();
		if (cleared.isEmpty()) return;
		final List<Authorization.BlockAuthorization> retained = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> !cleared.contains(block)).toList();
		final String requestId = vehicle.request.getRequestId();
		final Set<String> retainedSections = retained.stream().flatMap(block -> block.sectionIds().stream()).collect(java.util.stream.Collectors.toSet());
		final Set<String> clearedSections = cleared.stream().flatMap(block -> block.sectionIds().stream()).collect(java.util.stream.Collectors.toSet());
		clearedSections.removeAll(retainedSections);
		clearedSections.removeAll(vehicle.sections);
		clearedSections.removeAll(vehicle.retainedProtection.blockSections());
		SectionStateManager.releaseSections(simulator, clearedSections, requestId);
		SectionStateManager.releaseBlocks(simulator, vehicle.retainedProtection.releasableBlockLockIds(blockLockIds(cleared), retained), requestId);
		for (final Authorization.BlockAuthorization block : cleared) {
			MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "block=" + block.blockId() + " section=" + block.sectionIds()
					+ " tailDistance=" + RouteMath.fmt(vehicle.tail) + " request=" + requestId + " reason=TAIL_PASSED_BLOCK_END");
		}
		final List<String> clearedJunctionResources = cleared.stream()
				.flatMap(block -> block.junctionMovementIds().stream()).toList();
		JunctionStateManager.release(simulator, vehicle.retainedProtection.releasableJunctionResources(clearedJunctionResources, retained), requestId);
		MtrbrDebugLog.event("MTRBR-AUTH-STALE", "vehicle=" + vehicle.vehicle.getId() + " request=" + requestId
				+ " headDistance=" + RouteMath.fmt(vehicle.head) + " clearedBlocks=" + cleared.stream().map(Authorization.BlockAuthorization::blockId).toList()
				+ " clearedSections=" + clearedSections + " reason=TAIL_PASSED_BLOCK_END");
		if (retained.isEmpty()) {
			vehicle.lastSafeBoundary = Math.max(vehicle.head, RouteMath.finiteOr(vehicle.lastSafeBoundary, vehicle.head));
			vehicle.authorizationRetryPending = true;
			enterAuthorizationRecovery(vehicle);
		}
		vehicle.authorization = retained.isEmpty() ? null : createAuthorization(simulator, STATES.get(simulator), vehicle, retained);
		if (!retained.isEmpty()) vehicle.authorizationRetryPending = false;
		if (!retained.isEmpty()) refreshActivityAuthorization(simulator, vehicle);
		vehicle.authorizationEndDistance = retained.stream().mapToDouble(Authorization.BlockAuthorization::endDistance).max().orElse(vehicle.head);
		vehicle.lastAuthorizationId = retained.isEmpty() ? vehicle.lastAuthorizationId : vehicle.authorization.getAuthorizationId();
		RouteDiagnostics.logAuthorizationLifecycle(simulator, vehicle, "AUTHORIZATION_PREFIX_RELEASED");
	}

	static void updateAuthorizedLifecycle(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request.getState() == RequestState.AUTHORIZED && vehicle.head >= vehicle.controlDistance) {
			vehicle.request.transitionIfChanged(RequestState.ACTIVE, "Entered authorized route");
		}
		final boolean routeCleared = vehicle.tail >= vehicle.endDistance
				&& (vehicle.authorization == null || vehicle.authorization.getBlockAuthorizations().stream()
						.noneMatch(block -> isBlockPhysicallyOccupied(vehicle, block)))
				&& vehicle.retainedProtection.isEmpty();
		if ((vehicle.request.getState() == RequestState.AUTHORIZED || vehicle.request.getState() == RequestState.ACTIVE) && routeCleared) {
			vehicle.request.transitionIfChanged(RequestState.PASSED, "Vehicle tail passed complete Request end");
		}
		// Request completion must obey the same physical-clearance test as Block release.
		if (routeCleared) {
			release(simulator, vehicle);
			vehicle.request.transitionIfChanged(RequestState.RELEASED, "Vehicle tail cleared route");
		}
	}

	static void release(Simulator simulator, VehicleState vehicle) {
		if (vehicle.request != null) CapacityLeaseManager.releaseUnenteredZones(simulator, vehicle.request.getRequestId());
		if (vehicle.authorization != null && vehicle.request != null) {
			MtrbrDebugLog.event("RELEASE-RESOURCES", "vehicle=" + vehicle.request.getVehicleId()
					+ " request=" + vehicle.request.getRequestId()
					+ " sections=" + vehicle.authorization.getSectionIds()
					+ " blocks=" + vehicle.authorization.getBlockIds());
			MtrbrDebugLog.event("AUTH", "released vehicle=" + vehicle.request.getVehicleId() + " request=" + vehicle.request.getRequestId() + " rails=" + vehicle.authorization.getSectionIds());
			SectionStateManager.releaseSections(simulator, vehicle.authorization.getSectionIds(), vehicle.request.getRequestId());
			SectionStateManager.releaseBlocks(simulator, blockLockIds(vehicle.authorization.getBlockAuthorizations()), vehicle.request.getRequestId());
			final List<String> resources = vehicle.authorization.getBlockAuthorizations().stream()
					.flatMap(block -> block.junctionMovementIds().stream()).distinct().toList();
			JunctionStateManager.release(simulator, resources, vehicle.request.getRequestId());
			vehicle.authorization = null;
		}
	}

	static void invalidateAuthorization(Simulator simulator, VehicleState vehicle, ReleaseReason reason, RequestState terminalState) {
		final Authorization authorization = vehicle.authorization;
		final String requestId = vehicle.request == null ? "" : vehicle.request.getRequestId();
		RouteOverride.clearOneShotOverride(simulator, vehicle, "authorization invalidated: " + reason);
		if (!requestId.isEmpty()) CapacityLeaseManager.releaseUnenteredZones(simulator, requestId);
		if (authorization != null) {
			vehicle.lastAuthorizationId = authorization.getAuthorizationId();
			final Set<String> pendingSections = new HashSet<>();
			for (final Authorization.BlockAuthorization block : authorization.getBlockAuthorizations()) {
				if (isBlockPhysicallyOccupied(vehicle, block)) pendingSections.addAll(block.sectionIds());
			}
			pendingSections.addAll(vehicle.sections);
			pendingSections.addAll(vehicle.retainedProtection.blockSections());
			final Set<String> immediateSections = new HashSet<>(authorization.getSectionIds());
			immediateSections.removeAll(pendingSections);
			SectionStateManager.releaseSections(simulator, immediateSections, requestId);
			final List<String> clearedBlockIds = new ArrayList<>();
			final List<String> clearedJunctionResources = new ArrayList<>();
			for (final Authorization.BlockAuthorization block : authorization.getBlockAuthorizations()) {
				if (isBlockPhysicallyOccupied(vehicle, block)) {
					registerPendingRelease(simulator, vehicle, block, reason);
				} else {
					clearedBlockIds.addAll(blockLockIds(List.of(block)));
					clearedJunctionResources.addAll(block.junctionMovementIds());
				}
			}
			vehicle.retainedProtection.retainSections(pendingSections, requestId, reason);
			SectionStateManager.releaseBlocks(simulator, vehicle.retainedProtection.releasableBlockLockIds(clearedBlockIds, List.of()), requestId);
			JunctionStateManager.release(simulator, vehicle.retainedProtection.releasableJunctionResources(clearedJunctionResources, List.of()), requestId);
			MtrbrDebugLog.event("MTRBR-RESOURCE-PENDING-RELEASE", "vehicleId=" + (vehicle.vehicle == null ? "<none>" : vehicle.vehicle.getId())
					+ " requestId=" + requestId + " authorizationId=" + authorization.getAuthorizationId()
					+ " reason=" + reason + " " + vehicle.retainedProtection);
		}
		vehicle.authorization = null;
		vehicle.activityAuthorization = null;
		if (RouteTurnback.isTurnbackHandoffActive(vehicle)) {
			vehicle.turnbackHandoff.fail();
			RouteTurnback.logTurnbackActivity(vehicle, "<pending>", "<invalid>", "HANDOFF_FAILED",
					"RESUME_NORMAL_GATE", "AUTHORIZATION_INVALIDATED_" + reason);
		}
		vehicle.authorizationRetryPending = false;
		vehicle.authorizationEndDistance = vehicle.head;
		RouteDiagnostics.logAuthorizationLifecycle(simulator, vehicle, "AUTHORIZATION_INVALIDATED_" + reason);
		if (vehicle.request != null && !isTerminal(vehicle.request.getState()) && vehicle.request.getState() != terminalState) {
			vehicle.request.transitionIfChanged(terminalState, "Authorization invalidated: " + reason);
		}
	}

	/** Simulation-thread dispatcher operation; the public facade only queues it and reports the result. */
	static DispatchResult revokeAuthorization(Simulator simulator, VehicleState vehicle) {
		if (vehicle == null || vehicle.request == null) return new DispatchResult(false, "request_missing", "");
		if (isTerminal(vehicle.request.getState())) return new DispatchResult(false, "request_terminal", vehicle.request.getState().name());
		if (!vehicle.request.canTransitionTo(RequestState.REVOKED)) return new DispatchResult(false, "request_state", vehicle.request.getState().name());
		invalidateAuthorization(simulator, vehicle, ReleaseReason.REVOKED, RequestState.REVOKED);
		vehicle.lastCheckedStateRevision = -1;
		vehicle.lastCheckedTick = -20;
		return new DispatchResult(true, "revoked", "");
	}

	static void releaseAll(Simulator simulator, VehicleState vehicle) {
		release(simulator, vehicle);
		vehicle.retainedProtection.releaseAll(simulator);
	}

	static void registerPendingRelease(Simulator simulator, VehicleState vehicle,
			Authorization.BlockAuthorization block, ReleaseReason reason) {
		final RetainedProtection.Block pending = vehicle.retainedProtection.retain(vehicle.request.getRequestId(), block, reason);
		RouteDiagnostics.logTurnbackHandoff(vehicle, pending, "RETAIN_PENDING", "PHYSICAL_SECTION_INTERSECTION");
		MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "vehicle=" + vehicle.vehicle.getId()
				+ " resource=block:" + block.blockId() + " tailDistance=" + RouteMath.fmt(vehicle.tail)
				+ " endDistance=" + RouteMath.fmt(block.endDistance()) + " released=false reason=PHYSICAL_SECTION_INTERSECTION");
	}

	/** Releases a pending Block, its Sections and its Junction resources as one tail-cleared unit. */
	static void releasePendingReleaseOccupancy(Simulator simulator, VehicleState vehicle) {
		final RetainedProtection.Release released = vehicle.retainedProtection.releaseCleared(simulator, vehicle.sections,
				vehicle.authorization == null ? List.of() : vehicle.authorization.getBlockAuthorizations());
		for (final RetainedProtection.Block pending : released.blocks()) {
			RouteDiagnostics.logTurnbackHandoff(vehicle, pending, "RELEASE_NOW", "NO_PHYSICAL_SECTION_INTERSECTION");
			MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "vehicle=" + vehicle.vehicle.getId()
					+ " resource=block:" + pending.blockId() + " tailDistance=" + RouteMath.fmt(vehicle.tail)
					+ " endDistance=" + RouteMath.fmt(pending.endDistance()) + " released=true reason=NO_PHYSICAL_SECTION_INTERSECTION");
			MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "vehicle=" + vehicle.vehicle.getId()
					+ " resource=junction:" + pending.junctionResources() + " owner=" + pending.owner()
					+ " released=true reason=BLOCK_PHYSICALLY_CLEARED");
		}
		for (final String sectionId : released.sections()) {
			MtrbrDebugLog.event("MTRBR-RESOURCE-RELEASE", "vehicle=" + vehicle.vehicle.getId()
					+ " resource=section:" + sectionId + " tailDistance=" + RouteMath.fmt(vehicle.tail)
					+ " endDistance=<cleared-block> released=true");
		}
	}

	/**
	 * A pending block is retained only while MTR's current physical occupancy still
	 * intersects the rail Sections projected by that old block occurrence. This is
	 * deliberately independent of the current path's distance direction.
	 */
	static boolean isBlockPhysicallyOccupied(VehicleState vehicle, Authorization.BlockAuthorization block) {
		return isBlockPhysicallyOccupied(vehicle, block.sectionIds());
	}

	static boolean isBlockPhysicallyOccupied(VehicleState vehicle, List<String> blockSections) {
		return !blockSections.isEmpty() && blockSections.stream().anyMatch(vehicle.sections::contains);
	}

}
