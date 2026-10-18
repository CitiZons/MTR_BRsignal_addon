package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.mtrbr.server.RouteRuntime.Clearance;
import org.mtrbr.server.RouteRuntime.ControlPoint;
import org.mtrbr.server.RouteRuntime.ControlRange;
import org.mtrbr.server.RouteRuntime.TurnbackPhaseFence;
import org.mtrbr.server.RouteRuntime.VehicleState;
import static org.mtrbr.server.RetainedProtection.blockLockIds;

/** Read-only fixed-block clearance planning and operational boundaries. Never acquires resources. */
final class RouteAuthorizationPlanner {
	private RouteAuthorizationPlanner() {}

	static final double BLOCK_ENTRY_FACE_TOLERANCE = 0.05;

	/** A proven current Block, not a guessed stopping distance or an invented signal. */
	static PathSnapshot.FaceTraversal containingBlockEntry(PathSnapshot path, List<PathSnapshot.FaceTraversal> faces, double head) {
		return faces.stream().filter(face -> face.distance() <= head + 1.0E-6)
				.filter(face -> path.getNextProtectionBoundary(face, faces).distance() > head + 1.0E-6)
				.max(Comparator.comparingDouble(PathSnapshot.FaceTraversal::distance)).orElse(null);
	}

	static ControlRange findControlRange(Simulator simulator, VehicleState vehicle) {
		final PathSnapshot path = vehicle.path;
		final double head = vehicle.head;
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, head, "CONTROL_RANGE");
		final List<PathSnapshot.FaceTraversal> faces = RouteTurnback.phaseFaces(simulator, vehicle, fence);
		final PathSnapshot.FaceTraversal containing = vehicle.hasRetainedControl() ? containingBlockEntry(path, faces, head) : null;
		final List<ControlPoint> ahead = faces.stream()
				.map(ControlPoint::new)
				.filter(point -> point.traversal().distance() > head)
				.toList();
		if (ahead.isEmpty() && containing == null) return null;
		final double controlDistance = containing == null ? ahead.get(0).traversal().distance() : head;
		final PathSnapshot.TurnbackWindow turnback = path.getNextTurnbackWindow(controlDistance);
		final double stopBoundary = turnback.stopDistance();
		final double turnbackBoundary = fence.fenceDistance();
		final double fiveSignalBoundary = ahead.size() > 4 ? ahead.get(4).traversal().distance() : path.getTotalDistance();
		final double shuntBoundary = ShuntSignalPolicy.boundary(simulator.dimension, path, faces, head);
		final double lookaheadEndDistance = Math.min(shuntBoundary, Math.min(stopBoundary, Math.min(turnbackBoundary, fiveSignalBoundary)));
		// A stopping point exactly at the first control face still needs a
		// Request. Keep a zero-length authorization window so SectionCheck marks
		// it denied and MovementGate holds the train at that face.
		final double effectiveLookaheadEnd = Math.max(controlDistance, lookaheadEndDistance);
		final List<String> signalFaceIds = ahead.stream()
				.filter(point -> point.traversal().distance() >= controlDistance && point.traversal().distance() <= effectiveLookaheadEnd)
				.map(point -> faceTraversalKey(point.traversal()))
				.toList();
		return new ControlRange(containing == null ? ahead.get(0).traversal().faceId() : containing.faceId(), controlDistance, effectiveLookaheadEnd, path.getTotalDistance(), 0, signalFaceIds);
	}

	/** 沿 Request 区段逐 Rail 检查；Section 的唯一单位就是一条无向 Rail。 */
	static Clearance clearancePrefix(Simulator simulator, VehicleState vehicle, double startDistance, double endDistance) {
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
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, startDistance, "AUTHORIZATION_PREFIX");
		final List<PathSnapshot.FaceTraversal> faces = RouteTurnback.phaseFaces(simulator, vehicle, fence);
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		final PathSnapshot.FaceTraversal containing = containingBlockEntry(vehicle.path, faces, startDistance);
		for (final PathSnapshot.FaceTraversal face : faces) {
			// Continue from the current head, but still accept the entry face of the
			// Block the head has just crossed in this simulation tick. Without this
			// tolerance, a head at 228.921 skips an entry face at 228.913 while the
			// next face equals the window end, yielding a false "no next block".
			if (face.distance() >= endDistance) continue;
			if (face.distance() < startDistance - BLOCK_ENTRY_FACE_TOLERANCE
					&& (containing == null || !face.key().sameIdentity(containing.key()))) continue;
			RouteDiagnostics.logRouteProjection(simulator, vehicle, faces, savedBlocks, face);
			final RouteProjection projection = RouteProjection.build(simulator, vehicle.path, faces, face, savedBlocks,
					SectionStateManager.getTopologyRevision(simulator));
			if (projection.result() != RouteProjection.Result.READY) {
				final String projectionFailure = projection.result() == RouteProjection.Result.BLOCK_DEFINITION_MISSING
						? "BLOCK_DEFINITION_MISSING: current occurrence has no stable block definition"
						: "EMPTY_PATH_SEGMENT: current occurrence has no traversals or sections";
				RouteDiagnostics.logAuthorizationExtendFail(vehicle, startDistance, endDistance, "<rejected>", projectionFailure);
				final String audit = "face=" + face.faceId() + " faceKey=" + face.key()
						+ " routeProjection=" + projection.result() + " action=WAIT_MAPPING";
				MtrbrDebugLog.event("BLOCK-ID", audit + " source=SAVED_DATA");
				break;
			}
			// Saved Blocks may straddle an operational boundary. Project the part up
			// to the hard authorization limit instead of either borrowing its far
			// side or refusing the entire prefix.
			final double candidateEnd = Math.min(projection.boundaryDistance(), endDistance);
			final boolean completeSavedBlock = projection.boundaryDistance() <= endDistance + 1.0E-6;
			final List<PathSnapshot.PathTraversal> candidateTraversals = projection.traversals().stream()
					.filter(traversal -> traversal.endDistance() > face.distance() && traversal.startDistance() < candidateEnd).toList();
			final List<PathSnapshot.FaceTraversalKey> projectedFaceKeys = candidateFaceKeys(vehicle.path, faces, candidateTraversals, face, candidateEnd);
			final List<PathSnapshot.FaceTraversalKey> boundaryFaceKeys = faces.stream()
					.filter(item -> candidateTraversals.stream().anyMatch(traversal -> traversal.index() == item.pathTraversalIndex()))
					.filter(item -> item.distance() >= face.distance() && item.distance() <= candidateEnd)
					.map(PathSnapshot.FaceTraversal::key).toList();
			if (projectedFaceKeys.isEmpty()) {
				RouteDiagnostics.logAuthorizationExtendFail(vehicle, startDistance, endDistance, projection.blockDefinitionId(), "key mismatch (entry-face projection)");
				MtrbrDebugLog.event("MTRBR-MAPPING-DIAGNOSTIC", "vehicle=" + vehicle.vehicle.getId()
						+ " request=" + vehicle.request.getRequestId()
						+ " classification=FACE_KEY_PROJECTION_MISMATCH"
						+ " faceKey=" + face.key() + " blockDefinition=" + projection.blockDefinitionId()
						+ " pathFingerprint=" + projection.pathFingerprint() + " action=WAIT_MAPPING");
				break;
			}
			final List<PathSnapshot.FaceTraversalKey> effectiveFaceKeys = combineFaceTraversalKeys(projectedFaceKeys, boundaryFaceKeys);
			final List<String> railIds = savedBlocks.getRailIds(projection.blockDefinitionId());
			// A stop within a fixed Block still protects that Block's full interlocking
			// resources. The shared definition ends before the reverse occurrence,
			// so it cannot borrow the departure leg's junction movements.
			final Authorization.BlockAuthorization candidate = new Authorization.BlockAuthorization(projection.blockDefinitionId(),
					candidateTraversals.stream().mapToInt(PathSnapshot.PathTraversal::index).min().orElse(-1),
					candidateTraversals.stream().mapToDouble(PathSnapshot.PathTraversal::startDistance).min().orElse(face.distance()),
					candidateEnd, vehicle.path.getSectionIds(candidateTraversals), candidateTraversals, effectiveFaceKeys, completeSavedBlock,
					projection.boundaryIdentity(), projection.boundaryDistance(), face.key(), projection.boundaryFaceKey(),
					projection.pathFingerprint(), railIds, projection.junctionMovementIds());
			RouteDiagnostics.auditCandidateBlock(simulator, vehicle, candidate, savedBlocks);
			if (!SectionStateManager.areBlocksAvailable(simulator, blockLockIds(List.of(candidate)), vehicle.request.getRequestId())) {
				RouteDiagnostics.logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "block conflict");
				MtrbrDebugLog.event("MTRBR-CONFLICT", "type=BLOCK vehicle=" + vehicle.request.getVehicleId()
						+ " blockedBy=<block-state> resource=" + blockLockIds(List.of(candidate))
						+ " range=" + RouteMath.fmt(candidate.startDistance()) + ".." + RouteMath.fmt(candidate.endDistance()));
				break;
			}
			final List<String> junctionResources = candidate.junctionMovementIds();
			if (JunctionStateManager.conflicts(simulator, junctionResources, vehicle.request.getRequestId())) {
				RouteDiagnostics.logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "junction conflict");
				MtrbrDebugLog.event("MTRBR-CONFLICT", "type=JUNCTION_MOVEMENT vehicle=" + vehicle.request.getVehicleId()
						+ " blockedBy=<junction-state> resource=" + junctionResources
						+ " range=" + RouteMath.fmt(candidate.startDistance()) + ".." + RouteMath.fmt(candidate.endDistance()));
				break;
			}
			final String blockAudit = "face=" + face.faceId() + " blockId=" + projection.blockDefinitionId() + " traversalIndex=" + face.pathTraversalIndex() + " nextBoundary=" + projection.boundaryIdentity() + " railCount=" + railIds.size();
			MtrbrDebugLog.event("BLOCK-ID", blockAudit + " rails=" + railIds + " source=ROUTE_PROJECTION");
			final SectionCheck.BlockResult check = SectionCheck.checkBlock(simulator, candidate.blockId(), candidate.sectionIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false);
			if (!check.safe()) {
				RouteDiagnostics.logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "section conflict");
				final SectionCheck.SectionResult result = check.sections().sections().stream().filter(item -> item.status() != SectionCheck.Status.AVAILABLE).findFirst().orElse(null);
				MtrbrDebugLog.event("MTRBR-FCFS-CONFLICT", "type=SECTION vehicle=" + vehicle.request.getVehicleId()
					+ " blockedBy=" + (result == null || result.state() == null ? "<unknown>" : result.state().occupiedBy)
					+ " resource=" + (result == null ? candidate.blockId() : result.sectionId())
						+ " range=" + RouteMath.fmt(candidate.startDistance()) + ".." + RouteMath.fmt(candidate.endDistance()));
				MtrbrDebugLog.event("CHECK", "vehicle=" + vehicle.request.getVehicleId()
						+ " request=" + vehicle.request.getRequestId()
					+ " block=" + candidate.blockId() + " rails=" + railIds
						+ " status=" + check.status()
						+ " rail=" + (result == null ? "-" : result.sectionId()));
				break;
			}
			final List<PathSnapshot.PathTraversal> blockTraversals = new ArrayList<>(candidateTraversals);
			if (blockTraversals.isEmpty()) {
				RouteDiagnostics.logAuthorizationExtendFail(vehicle, startDistance, endDistance, candidate.blockId(), "no next block");
				break;
			}
			authorizedBlockIds.add(candidate.blockId());
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
			authorizedBlocks.add(candidate);
			authorizedEnd = Math.max(authorizedEnd, blockEnd);
		}
		return new Clearance(List.copyOf(authorizedRailIds), List.copyOf(authorizedBlockIds), List.copyOf(authorizedTraversals), List.copyOf(authorizedFaces), List.copyOf(authorizedBlocks), authorizedEnd);
	}

	/** The hard upper bound applied at every authorization construction site. */
	static double authorizationBoundary(Simulator simulator, VehicleState vehicle, double startDistance) {
		final double phaseStart = Math.max(vehicle.head, startDistance - 1.0E-6);
		final PathSnapshot.TurnbackWindow turnback = vehicle.path.getNextTurnbackWindow(phaseStart);
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, phaseStart, "AUTHORIZATION");
		final double stopBoundary = turnback.stopDistance();
		final double turnbackBoundary = fence.fenceDistance();
		final double fiveSignalBoundary = RouteTurnback.phaseFaces(simulator, vehicle, fence).stream()
				.filter(face -> face.distance() > Math.max(vehicle.head, startDistance) + 1.0E-6)
				.skip(4)
				.mapToDouble(PathSnapshot.FaceTraversal::distance)
				.findFirst().orElse(vehicle.path.getTotalDistance());
		final double shuntBoundary = ShuntSignalPolicy.boundary(simulator.dimension, vehicle.path, RouteTurnback.phaseFaces(simulator, vehicle, fence), vehicle.head);
		return Math.min(shuntBoundary, Math.min(stopBoundary, Math.min(turnbackBoundary, fiveSignalBoundary)));
	}

	/**
	 * A hard authorization window may end inside a Saved Block.  The retained
	 * BlockAuthorization therefore carries the immutable occurrence and its real
	 * protection boundary, so extending the window never has to rediscover an
	 * already-passed entry face.
	 */
	static Clearance continueSavedBlockPrefix(Simulator simulator, VehicleState vehicle, double requestedEnd) {
		final Authorization.BlockAuthorization incomplete = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> !block.completeSavedBlock())
				.filter(block -> vehicle.head >= block.endDistance() - 1.0E-6)
				.filter(block -> block.savedBlockEndDistance() > vehicle.head + 1.0E-6)
				.filter(block -> !hasCompletedSavedBlockProjection(vehicle.authorization.getBlockAuthorizations(), block))
				.max(Comparator.comparingDouble(Authorization.BlockAuthorization::endDistance)).orElse(null);
		if (incomplete == null) {
			final boolean crossedIncomplete = vehicle.authorization.getBlockAuthorizations().stream()
					.anyMatch(block -> !block.completeSavedBlock() && block.savedBlockEndDistance() <= vehicle.head + 1.0E-6);
			if (crossedIncomplete) RouteDiagnostics.logSavedBlockContinue(vehicle, "<none>", "BLOCK_COMPLETE", vehicle.authorizationEndDistance, vehicle.authorizationEndDistance, false);
			return null;
		}
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, vehicle.head, "SAVED_BLOCK_CONTINUATION");
		final List<PathSnapshot.FaceTraversal> faces = RouteTurnback.phaseFaces(simulator, vehicle, fence);
		final PathSnapshot.FaceTraversal entry = faces.stream()
				.filter(face -> face.key().sameIdentity(incomplete.entryFaceTraversalKey())).findFirst().orElse(null);
		if (entry == null) {
			RouteDiagnostics.logSavedBlockContinue(vehicle, incomplete.blockId(), "PATH_INVALID", incomplete.endDistance(), incomplete.savedBlockEndDistance(), false);
			return null;
		}
		final PathSnapshot.ProtectionBoundary boundary = vehicle.path.getProtectionBoundary(entry, faces, incomplete.savedBlockBoundaryId());
		if (boundary == null || Math.abs(boundary.distance() - incomplete.savedBlockEndDistance()) > 1.0E-6
				|| boundary.distance() <= vehicle.head + 1.0E-6) {
			RouteDiagnostics.logSavedBlockContinue(vehicle, incomplete.blockId(), "PATH_INVALID", incomplete.endDistance(), incomplete.savedBlockEndDistance(), false);
			return null;
		}
		final double continuationEnd = Math.min(boundary.distance(), requestedEnd);
		if (continuationEnd <= vehicle.head + 1.0E-6) return null;
		final List<PathSnapshot.PathTraversal> traversals = vehicle.path.getTraversalsBetween(vehicle.head, continuationEnd).stream()
				.filter(traversal -> incomplete.savedBlockRailIds().contains(traversal.sectionId())).toList();
		if (traversals.isEmpty()) {
			RouteDiagnostics.logSavedBlockContinue(vehicle, incomplete.blockId(), "PATH_INVALID", incomplete.endDistance(), boundary.distance(), false);
			return null;
		}
		final boolean completeSavedBlock = boundary.distance() <= requestedEnd + 1.0E-6;
		final List<PathSnapshot.FaceTraversalKey> faceKeys = candidateFaceKeys(vehicle.path, faces, traversals, entry, continuationEnd);
		if (faceKeys.isEmpty()) {
			RouteDiagnostics.logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, requestedEnd, incomplete.blockId(), "key mismatch (entry-face projection)");
			MtrbrDebugLog.event("MTRBR-MAPPING-DIAGNOSTIC", "vehicle=" + vehicle.vehicle.getId()
					+ " request=" + vehicle.request.getRequestId()
					+ " classification=FACE_KEY_PROJECTION_MISMATCH"
					+ " faceKey=" + entry.key() + " blockDefinition=" + incomplete.blockId()
					+ " pathFingerprint=" + incomplete.pathFingerprint() + " action=WAIT_MAPPING");
			return null;
		}
		final Authorization.BlockAuthorization candidate = new Authorization.BlockAuthorization(incomplete.blockId(),
				traversals.get(0).index(), vehicle.head, continuationEnd, vehicle.path.getSectionIds(traversals), traversals,
				faceKeys, completeSavedBlock, incomplete.savedBlockBoundaryId(), boundary.distance(), entry.key(), incomplete.boundaryFaceTraversalKey(),
				incomplete.pathFingerprint(), incomplete.savedBlockRailIds(), incomplete.junctionMovementIds());
		RouteDiagnostics.auditCandidateBlock(simulator, vehicle, candidate, SignalBlockSavedData.getSnapshot(simulator.dimension));
		if (!SectionStateManager.areBlocksAvailable(simulator, blockLockIds(List.of(candidate)), vehicle.request.getRequestId())) {
			RouteDiagnostics.logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, requestedEnd, candidate.blockId(), "block conflict");
			return new Clearance(List.of(), List.of(), List.of(), List.of(), List.of(), vehicle.authorizationEndDistance);
		}
		final List<String> junctionResources = candidate.junctionMovementIds();
		if (JunctionStateManager.conflicts(simulator, junctionResources, vehicle.request.getRequestId())) {
			RouteDiagnostics.logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, requestedEnd, candidate.blockId(), "junction conflict");
			return new Clearance(List.of(), List.of(), List.of(), List.of(), List.of(), vehicle.authorizationEndDistance);
		}
		final SectionCheck.BlockResult check = SectionCheck.checkBlock(simulator, candidate.blockId(), candidate.sectionIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false);
		if (!check.safe()) {
			RouteDiagnostics.logAuthorizationExtendFail(vehicle, vehicle.authorizationEndDistance, requestedEnd, candidate.blockId(), "section conflict");
			return new Clearance(List.of(), List.of(), List.of(), List.of(), List.of(), vehicle.authorizationEndDistance);
		}
		RouteDiagnostics.logSavedBlockContinue(vehicle, candidate.blockId(), "CONTINUE", incomplete.endDistance(), boundary.distance(), completeSavedBlock);
		return new Clearance(candidate.sectionIds(), List.of(candidate.blockId()), candidate.traversals(), candidate.faceTraversalKeys(), List.of(candidate), candidate.endDistance());
	}

	static boolean hasContinuableSavedBlock(VehicleState vehicle) {
		return vehicle.authorization != null && vehicle.authorization.getBlockAuthorizations().stream()
				.anyMatch(block -> !block.completeSavedBlock() && vehicle.head >= block.endDistance() - 1.0E-6
						&& block.savedBlockEndDistance() > vehicle.head + 1.0E-6
						&& !hasCompletedSavedBlockProjection(vehicle.authorization.getBlockAuthorizations(), block));
	}

	static boolean hasCompletedSavedBlockProjection(List<Authorization.BlockAuthorization> blocks, Authorization.BlockAuthorization incomplete) {
		return blocks.stream().anyMatch(block -> block != incomplete && block.completeSavedBlock()
				&& block.blockId().equals(incomplete.blockId())
				&& block.entryFaceTraversalKey().sameIdentity(incomplete.entryFaceTraversalKey())
				&& block.endDistance() >= incomplete.savedBlockEndDistance() - 1.0E-6);
	}

	/** Extension is always one complete block, never a partial prefix of one. */
	static Clearance firstBlock(Clearance clearance) {
		if (clearance.blockAuthorizations().isEmpty()) {
			return clearance;
		}
		final Authorization.BlockAuthorization block = clearance.blockAuthorizations().get(0);
		return new Clearance(block.sectionIds(), List.of(block.blockId()), block.traversals(), block.faceTraversalKeys(), List.of(block), block.endDistance());
	}

	static double nextBlockEnd(Simulator simulator, VehicleState vehicle, double afterDistance) {
		final TurnbackPhaseFence fence = RouteTurnback.turnbackPhaseFence(simulator, vehicle, afterDistance, "AUTHORIZATION_EXTENSION");
		final List<PathSnapshot.FaceTraversal> faces = RouteTurnback.phaseFaces(simulator, vehicle, fence);
		final SignalBlockSavedData.Snapshot savedBlocks = SignalBlockSavedData.getSnapshot(simulator.dimension);
		for (final PathSnapshot.FaceTraversal face : faces) {
			if (face.distance() + 1.0E-6 < afterDistance) continue;
			final RouteProjection projection = RouteProjection.build(simulator, vehicle.path, faces, face, savedBlocks,
					SectionStateManager.getTopologyRevision(simulator));
			if (projection.result() == RouteProjection.Result.READY && projection.boundaryDistance() > afterDistance + 1.0E-6) return projection.boundaryDistance();
		}
		return afterDistance;
	}

	static List<PathSnapshot.FaceTraversalKey> combineFaceTraversalKeys(List<PathSnapshot.FaceTraversalKey> first, List<PathSnapshot.FaceTraversalKey> second) {
		final java.util.LinkedHashSet<PathSnapshot.FaceTraversalKey> keys = new java.util.LinkedHashSet<>(first);
		keys.addAll(second);
		return List.copyOf(keys);
	}

	static String faceTraversalKey(PathSnapshot.FaceTraversal traversal) {
		return traversal.key().toString();
	}

	static List<PathSnapshot.FaceTraversalKey> candidateFaceKeys(PathSnapshot path, List<PathSnapshot.FaceTraversal> faces,
			List<PathSnapshot.PathTraversal> traversals, PathSnapshot.FaceTraversal entryFace, double endDistance) {
		final double startDistance = entryFace.distance();
		final Set<Integer> indexes = traversals.stream().map(PathSnapshot.PathTraversal::index).collect(java.util.stream.Collectors.toSet());
		final java.util.LinkedHashSet<PathSnapshot.FaceTraversalKey> keys = faces.stream().filter(PathSnapshot::isDirectionMatched)
				.filter(face -> indexes.contains(face.pathTraversalIndex()))
				.filter(face -> face.distance() >= startDistance && face.distance() < endDistance)
				.map(PathSnapshot.FaceTraversal::key).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
		return List.copyOf(keys);
	}
}
