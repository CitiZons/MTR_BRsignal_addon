package org.mtrbr.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.mtr.mapping.holder.World;
import org.mtr.mod.block.BlockSignalBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.mtrbr.logic.SignalLogic;

/**
 * 覆盖 MTR 信号机的 aspect 判定：
 * 原版 getActualAspect 按占用后的秒数冷却变色，这里改为读取我们的闭塞链逻辑
 * （红-单黄-双黄-绿，最多向后传递 4 个信号，未开放进路默认红灯）。
 */
@Mixin(BlockSignalBase.BlockEntityBase.class)
public abstract class BlockSignalBlockEntityMixin {

	@Inject(method = "getActualAspect", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtrbr$overrideAspect(boolean occupied, boolean isBackSide, CallbackInfoReturnable<Integer> cir) {
		final BlockSignalBase.BlockEntityBase self = (BlockSignalBase.BlockEntityBase) (Object) this;
		final World world = self.getWorld2();
		if (world != null) {
			final Level level = world.data;
			final org.mtr.mapping.holder.BlockPos pos = self.getPos2();
			cir.setReturnValue(SignalLogic.getSignalAspect(level, new BlockPos(pos.getX(), pos.getY(), pos.getZ()), self, isBackSide));
		}
	}
}
