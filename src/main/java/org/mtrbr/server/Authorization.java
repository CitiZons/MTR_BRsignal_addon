package org.mtrbr.server;

import java.util.List;
import java.util.LinkedHashSet;

/** Server-side authorization for one complete RouteRequest. */
public final class Authorization {
	private final String authorizationId;
	private final String requestId;
	private final List<String> sectionIds;
	private final List<String> blockIds;
	private final List<PathSnapshot.PathTraversal> traversals;
	private final List<PathSnapshot.FaceTraversalKey> faceTraversalKeys;
	private final List<String> pathNodes;
	/** Ordered physical block ranges on the immutable vehicle path. */
	private final List<BlockAuthorization> blockAuthorizations;
	private final long topologyRevision;
	private final long revision;
	private final boolean manualDrivingOverride;

	public Authorization(String authorizationId, String requestId, List<BlockAuthorization> blockAuthorizations, List<String> pathNodes, long topologyRevision, long revision, boolean manualDrivingOverride) {
		this.authorizationId = authorizationId;
		this.requestId = requestId;
		this.blockAuthorizations = List.copyOf(blockAuthorizations);
		final LinkedHashSet<String> sections = new LinkedHashSet<>();
		final LinkedHashSet<String> blocks = new LinkedHashSet<>();
		final LinkedHashSet<PathSnapshot.PathTraversal> pathTraversals = new LinkedHashSet<>();
		final LinkedHashSet<PathSnapshot.FaceTraversalKey> faces = new LinkedHashSet<>();
		for (final BlockAuthorization block : this.blockAuthorizations) {
			blocks.add(block.blockId());
			sections.addAll(block.sectionIds());
			pathTraversals.addAll(block.traversals());
			faces.addAll(block.faceTraversalKeys());
		}
		this.sectionIds = List.copyOf(sections);
		this.blockIds = List.copyOf(blocks);
		this.traversals = List.copyOf(pathTraversals);
		this.faceTraversalKeys = List.copyOf(faces);
		this.pathNodes = List.copyOf(pathNodes);
		this.topologyRevision = topologyRevision;
		this.revision = revision;
		this.manualDrivingOverride = manualDrivingOverride;
	}

	public String getAuthorizationId() {
		return authorizationId;
	}

	public String getRequestId() {
		return requestId;
	}

	public List<String> getSectionIds() {
		return sectionIds;
	}

	public List<String> getBlockIds() {
		return blockIds;
	}

	public List<BlockAuthorization> getBlockAuthorizations() {
		return blockAuthorizations;
	}

	public List<PathSnapshot.PathTraversal> getTraversals() {
		return traversals;
	}

	public List<PathSnapshot.FaceTraversalKey> getFaceTraversalKeys() {
		return faceTraversalKeys;
	}

	/** Ordinary topology nodes used for path projection only; they are not locks. */
	public List<String> getPathNodes() {
		return pathNodes;
	}

	/** Conflict nodes are deliberately separate from ordinary path topology. */
	public List<String> getConflictNodes() {
		return List.of();
	}

	public long getTopologyRevision() {
		return topologyRevision;
	}

	public long getRevision() {
		return revision;
	}

	public boolean isManualDrivingOverride() {
		return manualDrivingOverride;
	}

	/** One saved Signal Block projected onto its actual immutable-path extent. */
	public record BlockAuthorization(String blockId, int traversalIndex, double startDistance, double endDistance,
			List<String> sectionIds, List<PathSnapshot.PathTraversal> traversals,
			List<PathSnapshot.FaceTraversalKey> faceTraversalKeys, boolean completeSavedBlock) {
		public BlockAuthorization {
			sectionIds = List.copyOf(sectionIds);
			traversals = List.copyOf(traversals);
			faceTraversalKeys = List.copyOf(faceTraversalKeys);
		}

		/** Stable identity for this physical Block visit within the immutable path. */
		public String occurrenceId() {
			return blockId + "@" + traversalIndex + (completeSavedBlock ? "" : "@" + String.format(java.util.Locale.ROOT, "%.6f", endDistance));
		}
	}
}
