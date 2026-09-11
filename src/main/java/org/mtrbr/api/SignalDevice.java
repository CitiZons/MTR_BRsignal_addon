package org.mtrbr.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Style-neutral signal device contract. Implementations must not own movement authority. */
public interface SignalDevice {
	ResourceLocation typeId();
	BlockPos position();
	boolean isPresent(ServerLevel level);
}
