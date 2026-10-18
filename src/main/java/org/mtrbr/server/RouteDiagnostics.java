package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mtrbr.server.RouteRequestManager.ActivityAuthorization;
import org.mtrbr.server.RouteRuntime.Clearance;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.VehicleState;
import static org.mtrbr.server.RouteRuntime.STATES;

/** Route projection, occupancy and authorization diagnostics. Does not grant or revoke resources. */
final class RouteDiagnostics {
	private RouteDiagnostics() {}

	/** Stage-one parallel RouteProjection audit. It deliberately has no authorization side effects. */
	static void logRouteProjection(Simulator simulator, VehicleState vehicle, List<PathSnapshot.FaceTraversal> faces,
			SignalBlockSavedData.Snapshot savedBlocks, PathSnapshot.FaceTraversal entry) {
		final RouteProjection projection = RouteProjection.build(simulator, vehicle.path, faces, entry, savedBlocks,
				SectionStateManager.getTopologyRevision(simulator));
		final String oldSavedProjection = savedBlocks.getOccurrenceBlockId(vehicle.path.getFingerprint(), entry.key());
		final String occurrenceKey = SignalBlockSavedData.occurrenceKey(vehicle.path.getFingerprint(), entry.key());
		final String occurrenceBoundary = oldSavedProjection.isBlank() ? "<none>" : savedBlocks.getBoundaryId(oldSavedProjection);
		final int occurrenceRailCount = oldSavedProjection.isBlank() ? 0 : savedBlocks.getRailIds(oldSavedProjection).size();
		final int computedRailCount = savedBlocks.getRailIds(projection.blockDefinitionId()).size();
		final String immediateBoundary = vehicle.path.getNextProtectionBoundary(entry, faces).id();
		final String classification;
		if (computedRailCount == 0) {
			classification = "COMPUTED_BLOCK_MISSING";
		} else if (oldSavedProjection.isBlank()) {
			classification = "OCCURRENCE_MISSING";
		} else if (occurrenceRailCount == 0) {
			classification = "OCCURRENCE_BLOCK_EXISTS";
		} else if (!immediateBoundary.equals(occurrenceBoundary)) {
			classification = "OCCURRENCE_BOUNDARY_MISMATCH";
		} else {
			classification = "OCCURRENCE_BLOCK_EXISTS";
		}
		final String signature = vehicle.request.getRequestId() + "|" + projection.entryFaceKey() + "|"
				+ projection.boundaryIdentity() + "|" + projection.blockDefinitionId() + "|" + projection.result() + "|" + oldSavedProjection;
		if (!vehicle.routeProjectionAuditSignatures.add(signature)) return;
		MtrbrDebugLog.event("MTRBR-ROUTE-PROJECTION", "vehicle=" + vehicle.vehicle.getId()
				+ " vehicleCode=" + RouteVehicleCodes.getVehicleCode(vehicle.vehicle.getId()) + " topologyRevision=" + projection.topologyRevision()
				+ " request=" + vehicle.request.getRequestId()
				+ " pathFingerprint=" + projection.pathFingerprint()
				+ " entryFace=" + projection.entryFaceKey() + "@" + RouteMath.fmt(projection.entryFaceDistance())
				+ " boundary=" + projection.boundaryIdentity() + "@" + RouteMath.fmt(projection.boundaryDistance())
				+ " traversals=" + projection.traversals().stream().map(PathSnapshot.PathTraversal::index).toList()
				+ " sections=" + projection.sectionIds()
				+ " junctionMovements=" + projection.junctionMovementIds()
				+ " blockDefinition=" + (projection.blockDefinitionId().isBlank() ? "<missing>" : projection.blockDefinitionId())
				+ " computedBoundary=" + projection.boundaryIdentity()
				+ " computedRailCount=" + computedRailCount
				+ " occurrenceKey=" + occurrenceKey
				+ " occurrenceBlock=" + (oldSavedProjection.isBlank() ? "<none>" : oldSavedProjection)
				+ " occurrenceBoundary=" + occurrenceBoundary
				+ " occurrenceRailCount=" + occurrenceRailCount
				+ " classification=" + classification
				+ " oldSavedProjection=" + (oldSavedProjection.isBlank() ? "<none>" : oldSavedProjection)
				+ " result=" + projection.result());
	}

	static void logSavedBlockContinue(VehicleState vehicle, String blockId, String action, double authorizationEnd, double blockEnd, boolean completeSavedBlock) {
		MtrbrDebugLog.event("MTRBR-SAVED-BLOCK-CONTINUE", "vehicle=" + vehicle.vehicle.getId()
				+ " block=" + blockId + " head=" + RouteMath.fmt(vehicle.head)
				+ " authorizationEnd=" + RouteMath.fmt(authorizationEnd) + " blockEnd=" + RouteMath.fmt(blockEnd)
				+ " completeSavedBlock=" + completeSavedBlock + " action=" + action);
	}

	static void logAuthorizationExtendState(VehicleState vehicle, String action) {
		if (vehicle.request == null) return;
		MtrbrDebugLog.event("MTRBR-AUTH-EXTEND-STATE", "vehicle=" + vehicle.vehicle.getId()
				+ " requestState=" + vehicle.request.getState()
				+ " authorizationEnd=" + RouteMath.fmt(vehicle.authorizationEndDistance)
				+ " headDistance=" + RouteMath.fmt(vehicle.head)
				+ " action=" + action);
	}

	static void logAuthorizationExtendFail(VehicleState vehicle, double currentEnd, double lookaheadEnd, String candidateBlock, String reason) {
		MtrbrDebugLog.event("MTRBR-AUTH-EXTEND-FAIL", "vehicle=" + vehicle.vehicle.getId()
				+ " currentEnd=" + RouteMath.fmt(currentEnd) + " lookaheadEnd=" + RouteMath.fmt(lookaheadEnd)
				+ " candidateBlock=" + candidateBlock + " reason=" + reason);
	}

	static void auditAuthorizationOverextension(Simulator simulator, VehicleState vehicle,
			PathSnapshot.TurnbackWindow turnback, double lookaheadEnd) {
		if (vehicle.authorization == null || vehicle.authorization.getBlockAuthorizations().isEmpty()) return;
		final double operationalBoundary = turnback.requiresTurnback() ? turnback.endDistance() : turnback.stopDistance();
		if (vehicle.authorizationEndDistance <= operationalBoundary + 1.0E-6) return;
		final Authorization.BlockAuthorization departure = vehicle.authorization.getBlockAuthorizations().stream()
				.filter(block -> block.startDistance() >= operationalBoundary - 1.0E-6).findFirst().orElse(null);
		if (departure == null) return;
		final String departureFaceKey = departure.faceTraversalKeys().stream().map(Object::toString).findFirst().orElse("<none>");
		MtrbrDebugLog.event("MTRBR-AUTH-OVEREXTEND", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + vehicle.request.getRequestId() + " headDistance=" + RouteMath.fmt(vehicle.head)
				+ " operationalBoundary=" + RouteMath.fmt(operationalBoundary)
				+ " authorizationEnd=" + RouteMath.fmt(vehicle.authorizationEndDistance)
				+ " lookaheadEnd=" + RouteMath.fmt(lookaheadEnd) + " departureBlock=" + departure.blockId()
				+ " departureFaceKey=" + departureFaceKey + " reason=AUTHORIZATION_PASSES_OPERATIONAL_BOUNDARY");
	}

	static void logAuthorizationLookahead(VehicleState vehicle, PathSnapshot.TurnbackWindow turnback, double lookaheadEnd) {
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
	}

	static void logAuthorizationEnd(VehicleState vehicle, String source, double endDistance) {
		if (vehicle.request == null) return;
		final Authorization authorization = vehicle.authorization;
		final double startDistance = authorization == null ? vehicle.head : authorization.getBlockAuthorizations().stream()
				.mapToDouble(Authorization.BlockAuthorization::startDistance).min().orElse(vehicle.head);
		final String blockId = authorization == null ? "-" : authorization.getBlockAuthorizations().stream()
				.map(Authorization.BlockAuthorization::blockId).reduce((first, second) -> second).orElse("-");
		final String faceTraversalKey = authorization == null ? "-" : authorization.getFaceTraversalKeys().stream()
				.map(Object::toString).reduce((first, second) -> second).orElse("-");
		MtrbrDebugLog.event("MTRBR-AUTH-END", "vehicle=" + vehicle.request.getVehicleId() + " request=" + vehicle.request.getRequestId()
				+ " source=" + source + " pathIndex=" + RouteMath.currentTraversalIndex(vehicle.path, vehicle.head)
				+ " authorizationStart=" + String.format("%.3f", startDistance)
				+ " authorizationEnd=" + String.format("%.3f", endDistance)
				+ " blockId=" + blockId + " faceTraversalKey=" + faceTraversalKey);
	}

	static void logAuthorizationLifecycle(Simulator simulator, VehicleState vehicle, String reason) {
		if (vehicle.vehicle == null) return;
		final String requestId = vehicle.request == null ? "<none>" : vehicle.request.getRequestId();
		final String state = vehicle.request == null ? RequestState.NONE.name() : vehicle.request.getState().name();
		final String auth = vehicle.authorization == null ? "<none>" : vehicle.authorization.getAuthorizationId();
		final String activity = vehicle.activityAuthorization == null ? "<none>"
				: (vehicle.activityAuthorization.valid() ? RouteMath.fmt(vehicle.activityAuthorization.endDistance()) : "invalid");
		final double boundary = vehicle.activityAuthorization != null && vehicle.activityAuthorization.valid()
				? vehicle.activityAuthorization.endDistance() : Math.max(vehicle.head, RouteMath.finiteOr(vehicle.lastSafeBoundary, vehicle.head));
		MtrbrDebugLog.event("MTRBR-AUTH-LIFECYCLE", "request=" + requestId + " state=" + state
				+ " authorization=" + auth + " activity=" + activity + " head=" + RouteMath.fmt(vehicle.head)
				+ " tail=" + RouteMath.fmt(vehicle.tail) + " boundary=" + RouteMath.fmt(boundary) + " reason=" + reason);
	}

	static String describeActivity(ActivityAuthorization activity) {
		if (activity == null) return "<none>";
		return activity.valid()
				? "end=" + RouteMath.fmt(activity.endDistance()) + ",faces=" + activity.faceTraversalKeys().size()
				: "<invalid>";
	}

	/** Stage-A read-only audit of directional candidates and generated face keys. */
	static void auditDirection(Simulator simulator, VehicleState vehicle) {
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
				final double difference = RouteMath.angleDifference(occurrence.travelAngle(), face.travelAngle());
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
		}
	}

	static void auditAuthorizationFailure(Simulator simulator, VehicleState vehicle, Clearance clearance) {
		final List<PathSnapshot.FaceTraversal> allFaces = vehicle.path.getFaceTraversals(simulator.dimension, ServerAspectManager.getFaceSnapshot(simulator.dimension));
		final List<PathSnapshot.FaceTraversal> faces = allFaces.stream().filter(PathSnapshot::isDirectionMatched)
				.filter(face -> face.distance() >= vehicle.controlDistance && face.distance() < vehicle.authorizationLookaheadEndDistance).toList();
		String reason = "KEY_MISMATCH";
		if (faces.isEmpty()) {
			reason = "NO_FACE_TRAVERSAL";
		} else {
			final SignalBlockSavedData.Snapshot saved = SignalBlockSavedData.getSnapshot(simulator.dimension);
			for (final PathSnapshot.FaceTraversal face : faces) {
				final RouteProjection projection = RouteProjection.build(simulator, vehicle.path,
						allFaces.stream().filter(PathSnapshot::isDirectionMatched).toList(), face, saved,
						SectionStateManager.getTopologyRevision(simulator));
				if (projection.result() != RouteProjection.Result.READY) {
					reason = "NO_BLOCK_MAPPING";
					break;
				}
				final SectionCheck.Status status = SectionCheck.checkBlock(simulator, projection.blockDefinitionId(), projection.sectionIds(), vehicle.request.getVehicleId(), vehicle.request.getRequestId(), false).status();
				if (status == SectionCheck.Status.OCCUPIED) { reason = "BLOCK_OCCUPIED"; break; }
				if (status == SectionCheck.Status.LOCKED || status == SectionCheck.Status.RESERVED || status == SectionCheck.Status.BLOCK_CONFLICT) { reason = status == SectionCheck.Status.BLOCK_CONFLICT ? "BLOCK_LOCKED" : status.name(); break; }
			}
		}
		final String audit = "vehicle=" + vehicle.vehicle.getId() + " request=" + (vehicle.request == null ? "<none>" : vehicle.request.getRequestId())
				+ " reason=" + reason + " clearanceBlocks=" + clearance.blockIds();
		MtrbrDebugLog.event("AUTH-FAIL", audit);
		System.out.println("[MTRBR-AUTH-FAIL] " + audit);
	}

	static void debugActivity(Simulator simulator, VehicleState vehicle, List<PathSnapshot.FaceTraversal> faces) {
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

	static void logTurnbackHandoff(VehicleState vehicle, RetainedProtection.Block pending, String action, String reason) {
		final String requestId = vehicle.request == null ? "<none>" : vehicle.request.getRequestId();
		MtrbrDebugLog.event("MTRBR-TURNBACK-HANDOFF", "vehicle=" + vehicle.vehicle.getId()
				+ " request=" + requestId + " owner=" + pending.owner() + " block=" + pending.blockId() + " occurrence=" + pending.occurrenceId()
				+ " trainRange=" + RouteMath.fmt(vehicle.tail) + ".." + RouteMath.fmt(vehicle.head)
				+ " blockRange=" + RouteMath.fmt(pending.startDistance()) + ".." + RouteMath.fmt(pending.endDistance())
				+ " path=" + vehicle.path.getFingerprint() + " reversed=" + vehicle.vehicle.getReversed()
				+ " entryFace=" + pending.entryFaceKey() + " sections=" + pending.sectionIds()
				+ " action=" + action + " reason=" + reason);
	}

	/** One-shot forensic snapshot for an ACTIVE request invalidated during observation. */
	static void logActivityInvalidation(Simulator simulator, VehicleState vehicle, String reason) {
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

	static void auditCandidateBlock(Simulator simulator, VehicleState vehicle, Authorization.BlockAuthorization candidate,
			SignalBlockSavedData.Snapshot savedBlocks) {
		final String vehicleId = String.valueOf(vehicle.request.getVehicleId());
		final String requestId = vehicle.request.getRequestId();
		final String candidateAudit = "vehicle=" + vehicleId + " request=" + requestId
				+ " candidateBlockId=" + candidate.blockId()
				+ " startDistance=" + RouteMath.fmt(candidate.startDistance()) + " endDistance=" + RouteMath.fmt(candidate.endDistance())
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
								+ " sections=" + candidate.sectionIds() + " range=" + RouteMath.fmt(candidate.startDistance()) + ".." + RouteMath.fmt(candidate.endDistance())
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
								+ " ownerBlock=" + owner.blockId() + " overlapStart=" + RouteMath.fmt(overlapStart)
								+ " overlapEnd=" + RouteMath.fmt(overlapEnd) + " sectionOverlap=" + sectionOverlap
								+ " blockOverlap=" + blockOverlap + " occurrenceOverlap=" + occurrenceOverlap
								+ " conflictType=" + type);
						MtrbrDebugLog.event("MTRBR-FCFS-CONFLICT", "type=" + type + " vehicle=" + vehicleId
								+ " blockedBy=" + other.vehicle.getId() + " resource=" + owner.blockId()
								+ " range=" + RouteMath.fmt(overlapStart) + ".." + RouteMath.fmt(overlapEnd));
						MtrbrDebugLog.event("MTRBR-CONFLICT", "type=" + type + " vehicle=" + vehicleId
								+ " blockedBy=" + other.vehicle.getId() + " resource=" + owner.blockId()
								+ " range=" + RouteMath.fmt(overlapStart) + ".." + RouteMath.fmt(overlapEnd));
					}
				}
				if (!pathNodeOverlap.isEmpty()) {
					MtrbrDebugLog.event("MTRBR-CONFLICT", "type=PATH_NODE_OVERLAP_IGNORED vehicle=" + vehicleId
							+ " blockedBy=" + other.vehicle.getId() + " resource=" + pathNodeOverlap
							+ " range=" + RouteMath.fmt(candidate.startDistance()) + ".." + RouteMath.fmt(candidate.endDistance()));
				}
			}
		}
		auditBlockProjection(simulator, vehicle, candidate, savedBlocks);
	}

	static void auditBlockProjection(Simulator simulator, VehicleState vehicle, Authorization.BlockAuthorization block,
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
					+ " faceDistance=" + RouteMath.fmt(face.distance()) + " blockRange=" + RouteMath.fmt(block.startDistance()) + ".." + RouteMath.fmt(block.endDistance())
					+ " directionMatched=" + directionMatched + " sectionMatched=" + sectionMatched
					+ " keyProjected=" + keyProjected + " savedBlockExists=" + savedExists);
			if (!inRange || !sectionMatched) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=BLOCK_SECTION_MISMATCH vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
			if (!directionMatched) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=BLOCK_DIRECTION_MISMATCH vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
			if (!keyProjected) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=BLOCK_KEY_MISSING vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
			if (!savedExists) MtrbrDebugLog.event("MTRBR-BLOCK-MISMATCH", "type=SAVED_BLOCK_MISSING vehicle=" + vehicle.vehicle.getId() + " block=" + block.blockId() + " face=" + face.key());
		}
	}
}
