package org.mtrbr.api;

import net.minecraft.core.BlockPos;

/** Immutable view consumed by the common authority engine. */
public record SignalFaceView(String id, BlockPos devicePos, BlockPos nodePos, boolean backSide, float travelAngle) {
}
