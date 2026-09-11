package org.mtrbr.api;

import net.minecraft.world.level.block.state.BlockState;

/** Pluggable block-type adapter used by topology discovery. */
public interface SignalDeviceAdapter {
	boolean supports(BlockState state);
	SignalDeviceKind kind();

	enum SignalDeviceKind {
		MAIN_SIGNAL,
		DISPLAY
	}
}
