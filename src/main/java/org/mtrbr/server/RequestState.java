package org.mtrbr.server;

/** Runtime state of one complete vehicle RouteRequest. */
public enum RequestState {
	NONE,
	OVERRIDE,
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
