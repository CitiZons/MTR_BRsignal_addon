package org.mtrbr.server;

/** Why a resource is waiting for the physical vehicle tail to clear it. */
public enum ReleaseReason {
	REVOKED,
	CANCELED,
	INVALID,
	TURNBACK,
	VEHICLE_REMOVED,
	SERVER_STOP
}
