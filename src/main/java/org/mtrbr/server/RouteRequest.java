package org.mtrbr.server;

import java.util.List;

/**
 * A complete path request from one vehicle. Signal faces are projections of
 * this object; they are not separate requests.
 */
public final class RouteRequest {
	private final String requestId;
	private final long vehicleId;
	private final String pathFingerprint;
	private final long generation;
	private final long createdTick;
	private double remainingPathDistance;
	private final List<String> sectionIds;
	private final List<PathSnapshot.PathTraversal> traversals;
	private final List<String> signalFaceIds;
	private RequestState state = RequestState.NONE;
	private String reason = "";
	private int manualPriority;

	public RouteRequest(long vehicleId, String pathFingerprint, long generation, long createdTick, List<String> sectionIds, List<PathSnapshot.PathTraversal> traversals, List<String> signalFaceIds) {
		this(vehicleId, pathFingerprint, generation, createdTick, Double.POSITIVE_INFINITY, sectionIds, traversals, signalFaceIds);
	}

	public RouteRequest(long vehicleId, String pathFingerprint, long generation, long createdTick, double remainingPathDistance, List<String> sectionIds, List<PathSnapshot.PathTraversal> traversals, List<String> signalFaceIds) {
		this.vehicleId = vehicleId;
		this.pathFingerprint = pathFingerprint;
		this.generation = generation;
		this.createdTick = createdTick;
		this.remainingPathDistance = remainingPathDistance;
		this.requestId = vehicleId + ":" + pathFingerprint + ":" + generation;
		this.sectionIds = List.copyOf(sectionIds);
		this.traversals = List.copyOf(traversals);
		this.signalFaceIds = List.copyOf(signalFaceIds);
	}

	public String getRequestId() {
		return requestId;
	}

	public long getVehicleId() {
		return vehicleId;
	}

	public String getPathFingerprint() {
		return pathFingerprint;
	}

	public long getGeneration() {
		return generation;
	}

	public long getCreatedTick() {
		return createdTick;
	}

	public double getRemainingPathDistance() {
		return remainingPathDistance;
	}

	/** Updated by the simulation thread while this request remains pending. */
	public void setRemainingPathDistance(double remainingPathDistance) {
		this.remainingPathDistance = Math.max(0, remainingPathDistance);
	}

	public List<String> getSectionIds() {
		return sectionIds;
	}

	public List<PathSnapshot.PathTraversal> getTraversals() {
		return traversals;
	}

	public List<String> getSignalFaceIds() {
		return signalFaceIds;
	}

	public RequestState getState() {
		return state;
	}

	public String getReason() {
		return reason;
	}

	/** A positive value is an audited dispatcher preference, never a safety bypass. */
	public int getManualPriority() {
		return manualPriority;
	}

	public void setManualPriority(int manualPriority) {
		this.manualPriority = manualPriority;
	}

	public void transitionTo(RequestState next, String reason) {
		if (!isTransitionAllowed(state, next)) {
			final String detail = "[MTRBR-INVALID-TRANSITION] vehicleId=" + vehicleId
					+ " requestId=" + requestId + " oldState=" + state + " newState=" + next
					+ " reason=" + (reason == null ? "" : reason);
			MtrbrDebugLog.event("MTRBR-INVALID-TRANSITION", detail);
			System.err.println(detail);
			throw new IllegalStateException(detail);
		}
		if (state != next) {
			MtrbrDebugLog.event("REQUEST", "vehicleId=" + vehicleId + " requestId=" + requestId
					+ " authorizationId=<managed-separately> " + state + "->" + next
					+ " reason=" + (reason == null ? "" : reason));
		}
		state = next;
		this.reason = reason == null ? "" : reason;
	}

	private static boolean isTransitionAllowed(RequestState from, RequestState to) {
		if (from == to) {
			return true;
		}
		return switch (from) {
			case NONE -> to == RequestState.APPROACHING || to == RequestState.CANCELED;
			case APPROACHING -> to == RequestState.REQUESTED || to == RequestState.CANCELED || to == RequestState.INVALID;
			case REQUESTED -> to == RequestState.CHECKING || to == RequestState.REVOKED || to == RequestState.CANCELED || to == RequestState.INVALID;
			case CHECKING -> to == RequestState.WAITING || to == RequestState.DENIED || to == RequestState.CANCELED || to == RequestState.REVOKED || to == RequestState.INVALID;
			case WAITING -> to == RequestState.CHECKING || to == RequestState.AUTHORIZED || to == RequestState.DENIED || to == RequestState.REVOKED || to == RequestState.INVALID || to == RequestState.CANCELED;
			case AUTHORIZED -> to == RequestState.ACTIVE || to == RequestState.CHECKING || to == RequestState.CANCELED || to == RequestState.REVOKED || to == RequestState.INVALID;
			case ACTIVE -> to == RequestState.CHECKING || to == RequestState.PASSED || to == RequestState.CANCELED || to == RequestState.REVOKED || to == RequestState.INVALID;
			case PASSED -> to == RequestState.RELEASED || to == RequestState.INVALID;
			case DENIED -> to == RequestState.CHECKING || to == RequestState.REVOKED || to == RequestState.CANCELED || to == RequestState.INVALID;
			case REVOKED -> to == RequestState.RELEASED || to == RequestState.CANCELED;
			case INVALID, CANCELED -> to == RequestState.RELEASED;
			case RELEASED -> false;
		};
	}
}
