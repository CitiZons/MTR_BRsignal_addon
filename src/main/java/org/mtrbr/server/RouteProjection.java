package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only projection of one already-selected immutable-path route segment.
 *
 * <p>The immutable MTR path selects the route. This value object merely derives
 * its protection boundary and resource set; it does not reserve, lock, or
 * choose a route.</p>
 */
public record RouteProjection(String pathFingerprint, long topologyRevision,
		PathSnapshot.FaceTraversalKey entryFaceKey, double entryFaceDistance,
		PathSnapshot.FaceTraversalKey boundaryFaceKey, String terminalBoundaryId, double boundaryDistance,
		List<PathSnapshot.PathTraversal> traversals, List<String> sectionIds,
		List<String> junctionMovementIds, String blockDefinitionId,
		double startDistance, double endDistance, Result result) {

	public RouteProjection {
		traversals = List.copyOf(traversals);
		sectionIds = List.copyOf(sectionIds);
		junctionMovementIds = List.copyOf(junctionMovementIds);
		terminalBoundaryId = terminalBoundaryId == null ? "" : terminalBoundaryId;
		blockDefinitionId = blockDefinitionId == null ? "" : blockDefinitionId;
	}

	public static RouteProjection build(Simulator simulator, PathSnapshot path,
			List<PathSnapshot.FaceTraversal> faces, PathSnapshot.FaceTraversal entry,
			SignalBlockSavedData.Snapshot definitions, long topologyRevision) {
		final Definition definition = define(simulator, path, faces, entry);
		final PathSnapshot.ProtectionBoundary boundary = definition.boundary();
		// Never substitute a saved occurrence's boundary for the selected path.
		final Result result;
		if (definition.traversals().isEmpty() || definition.sectionIds().isEmpty()) {
			result = Result.EMPTY_PATH_SEGMENT;
		} else if (definitions.getRailIds(definition.blockDefinitionId()).isEmpty()) {
			result = Result.BLOCK_DEFINITION_MISSING;
		} else {
			result = Result.READY;
		}
		return new RouteProjection(path.getFingerprint(), topologyRevision, entry.key(), entry.distance(),
				boundary.face() == null ? null : boundary.face().key(), boundary.isTerminal() ? boundary.id() : "", boundary.distance(),
				definition.traversals(), definition.sectionIds(), definition.junctionMovementIds(), definition.blockDefinitionId(),
				entry.distance(), boundary.distance(), result);
	}

	/** The same fixed-Block compiler is used by persistence and by live authorization. */
	public static Definition define(Simulator simulator, PathSnapshot path,
			List<PathSnapshot.FaceTraversal> faces, PathSnapshot.FaceTraversal entry) {
		final PathSnapshot.ProtectionBoundary boundary = path.getNextProtectionBoundary(entry, faces);
		final List<PathSnapshot.PathTraversal> traversals = path.getTraversalsBetween(entry.distance(), boundary.distance());
		final List<String> junctionMovements = JunctionStateManager.resourcesFor(simulator, traversals);
		return new Definition(boundary, traversals, path.getSectionIds(traversals), junctionMovements,
				blockDefinitionId(entry, boundary, traversals, junctionMovements));
	}

	/** A definition is geometry/resources only; it is not clearance or an authorization. */
	public record Definition(PathSnapshot.ProtectionBoundary boundary, List<PathSnapshot.PathTraversal> traversals,
			List<String> sectionIds, List<String> junctionMovementIds, String blockDefinitionId) {
		public Definition {
			traversals = List.copyOf(traversals);
			sectionIds = List.copyOf(sectionIds);
			junctionMovementIds = List.copyOf(junctionMovementIds);
		}
	}

	/** Stable identity for a physical directed route variant, independent of a vehicle/path prefix. */
	public static String blockDefinitionId(PathSnapshot.FaceTraversal entry, PathSnapshot.ProtectionBoundary boundary,
			List<PathSnapshot.PathTraversal> traversals, List<String> junctionMovements) {
		final String traversalIdentity = traversals.stream()
				.map(traversal -> traversal.sectionId() + ":" + traversal.startNode() + ">" + traversal.endNode() + ":" + traversal.travelAngle()
						+ ":r=" + traversal.reversePositions())
				.collect(Collectors.joining(","));
		final String movementIdentity = String.join(",", JunctionStateManager.blockDefinitionMovements(junctionMovements));
		return entry.faceId() + "->" + boundary.id() + "|dir=" + entry.direction()
				+ "|traversals=" + traversalIdentity + "|junctions=" + movementIdentity;
	}

	public String boundaryIdentity() {
		return boundaryFaceKey == null ? terminalBoundaryId : boundaryFaceKey.toString();
	}

	public enum Result {
		READY,
		BLOCK_DEFINITION_MISSING,
		EMPTY_PATH_SEGMENT
	}
}
