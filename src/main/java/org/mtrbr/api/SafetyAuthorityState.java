package org.mtrbr.api;

/** Movement authority is independent from any signal style or lamp sequence. */
public enum SafetyAuthorityState {
	STOP,
	PROCEED,
	APPROACH,
	RESTRICTED,
	SHUNT_PROCEED
}
