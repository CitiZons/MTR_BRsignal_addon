package org.mtrbr.server;

import java.util.List;

/** Server-side authorization for one complete RouteRequest. */
public final class Authorization {
	private final String authorizationId;
	private final String requestId;
	private final List<String> sectionIds;
	private final List<String> blockIds;
	private final List<PathSnapshot.PathTraversal> traversals;
	private final List<PathSnapshot.FaceTraversalKey> faceTraversalKeys;
	private final List<String> nodeKeys;
	private final long topologyRevision;
	private final long revision;
	private final boolean manualDrivingOverride;

	public Authorization(String authorizationId, String requestId, List<String> sectionIds, List<String> blockIds, List<PathSnapshot.PathTraversal> traversals, List<PathSnapshot.FaceTraversalKey> faceTraversalKeys, List<String> nodeKeys, long topologyRevision, long revision, boolean manualDrivingOverride) {
		this.authorizationId = authorizationId;
		this.requestId = requestId;
		this.sectionIds = List.copyOf(sectionIds);
		this.blockIds = List.copyOf(blockIds);
		this.traversals = List.copyOf(traversals);
		this.faceTraversalKeys = List.copyOf(faceTraversalKeys);
		this.nodeKeys = List.copyOf(nodeKeys);
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

	public List<PathSnapshot.PathTraversal> getTraversals() {
		return traversals;
	}

	public List<PathSnapshot.FaceTraversalKey> getFaceTraversalKeys() {
		return faceTraversalKeys;
	}

	/** 该授权路径经过的节点；同一节点同时只能被一条进路开放。 */
	public List<String> getNodeKeys() {
		return nodeKeys;
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
}
