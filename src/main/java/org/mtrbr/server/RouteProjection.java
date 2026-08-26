package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.SignalBlockSavedData;

import java.util.List;

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
		final PathSnapshot.ProtectionBoundary boundary = path.getNextProtectionBoundary(entry, faces);
		final List<PathSnapshot.PathTraversal> traversals = path.getTraversalsBetween(entry.distance(), boundary.distance());
		final List<String> sections = path.getSectionIds(traversals);
		final List<String> junctionMovements = JunctionStateManager.resourcesFor(simulator, traversals);
		// The boundary is selected above from immutablePath. Saved data is queried
		// only for the stable definition named by that already-selected identity.
		final String blockDefinitionId = entry.faceId() + "->" + boundary.id();
		final Result result;
		if (traversals.isEmpty() || sections.isEmpty()) {
			result = Result.EMPTY_PATH_SEGMENT;
		} else if (definitions.getRailIds(blockDefinitionId).isEmpty()) {
			result = Result.BLOCK_DEFINITION_MISSING;
		} else {
			result = Result.READY;
		}
		return new RouteProjection(path.getFingerprint(), topologyRevision, entry.key(), entry.distance(),
				boundary.face() == null ? null : boundary.face().key(), boundary.isTerminal() ? boundary.id() : "", boundary.distance(),
				traversals, sections, junctionMovements, blockDefinitionId, entry.distance(), boundary.distance(), result);
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
