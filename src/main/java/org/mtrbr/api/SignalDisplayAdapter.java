package org.mtrbr.api;

/** Publishes a style's display state; it cannot grant or revoke movement authority. */
public interface SignalDisplayAdapter<A> {
	void publish(SignalFaceView face, A aspect);
}
