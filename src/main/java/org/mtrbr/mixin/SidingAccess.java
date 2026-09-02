package org.mtrbr.mixin;

import org.mtr.core.data.Siding;
import org.mtr.core.data.PathData;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取 MTR 车场的发车时刻表，用于“出库前 10 秒开放”的出库信号。 */
@Mixin(Siding.class)
public interface SidingAccess {

	@Accessor(value = "departures", remap = false)
	LongArrayList mtrbr$getDepartures();

	@Accessor(value = "pathSidingToMainRoute", remap = false)
	ObjectArrayList<PathData> mtrbr$getPathSidingToMainRoute();

	@Accessor(value = "pathMainRoute", remap = false)
	ObjectArrayList<PathData> mtrbr$getPathMainRoute();

	@Accessor(value = "pathMainRouteToSiding", remap = false)
	ObjectArrayList<PathData> mtrbr$getPathMainRouteToSiding();
}
