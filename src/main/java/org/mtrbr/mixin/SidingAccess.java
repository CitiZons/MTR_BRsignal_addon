package org.mtrbr.mixin;

import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取 MTR 车场的发车时刻表，用于“出库前 10 秒开放”的出库信号。 */
@Mixin(Siding.class)
public interface SidingAccess {

	@Accessor(value = "departures", remap = false)
	LongArrayList mtrbr$getDepartures();
}
