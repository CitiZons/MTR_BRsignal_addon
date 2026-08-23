package org.mtrbr.server;

/** Runtime state of one complete vehicle RouteRequest. */
public enum RequestState {
	NONE,
	APPROACHING,
	REQUESTED,
	CHECKING,
	WAITING,
	AUTHORIZED,
	ACTIVE,
	PASSED,
	RELEASED,
	DENIED,
	REVOKED,
	INVALID,
	CANCELED
}
