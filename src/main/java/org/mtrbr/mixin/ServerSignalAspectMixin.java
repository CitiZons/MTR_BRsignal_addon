package org.mtrbr.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.mtr.mapping.holder.World;
import org.mtr.mod.block.BlockSignalBase;
import org.mtrbr.server.ServerAspect;
import org.mtrbr.server.ServerAspectManager;
import org.mtrbr.client.ServerAspectCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 单一 Aspect 入口：服务端读权威结果，客户端读只读缓存；缺失一律红灯。 */
@Mixin(BlockSignalBase.BlockEntityBase.class)
public abstract class ServerSignalAspectMixin {
	@Inject(method = "getActualAspect", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtrbr$serverAspect(boolean occupied, boolean isBackSide, CallbackInfoReturnable<Integer> callback) {
		final BlockSignalBase.BlockEntityBase self = (BlockSignalBase.BlockEntityBase) (Object) this;
		final World world = self.getWorld2();
		if (world == null) {
			return;
		}
		final org.mtr.mapping.holder.BlockPos pos = self.getPos2();
		final BlockPos signalPos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
		if (world.data instanceof ServerLevel level) {
			final ServerAspect aspect = ServerAspectManager.get(level, signalPos, isBackSide);
			callback.setReturnValue(aspect == null ? ServerAspect.RED.getValue() : aspect.getValue());
			return;
		}
		final Integer aspect = ServerAspectCache.get(signalPos, isBackSide);
		callback.setReturnValue(aspect == null ? ServerAspect.RED.getValue() : aspect);
	}
}
