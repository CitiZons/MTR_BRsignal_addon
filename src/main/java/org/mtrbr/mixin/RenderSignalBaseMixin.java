package org.mtrbr.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;

/** Keep MTR's native signal aspect lookup working for explicitly bound nodes beyond its default 5-block scan. */
@Mixin(targets = "org.mtr.mod.render.RenderSignalBase", remap = false)
public abstract class RenderSignalBaseMixin {
	@ModifyConstant(method = "getNodePos", constant = @Constant(intValue = 4))
	private static int mtrbr$expandHorizontalNodeSearch(int value) { return 16; }

	@ModifyConstant(method = "getNodePos", constant = @Constant(intValue = 5))
	private static int mtrbr$expandVerticalNodeSearch(int value) { return 16; }
}
