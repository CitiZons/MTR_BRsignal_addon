package org.mtrbr.server;

import net.minecraft.core.BlockPos;

/** Server-owned directional control boundary for one visible MTR signal face. */
public record SignalFace(String id, BlockPos signalPos, BlockPos nodePos, boolean backSide, float travelAngle) {
}
