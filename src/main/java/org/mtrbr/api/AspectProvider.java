package org.mtrbr.api;

/** Maps a style-neutral safety state to a style-specific displayed aspect. */
public interface AspectProvider<S, A> {
	A display(S safetyState, SignalFaceView face);
}
