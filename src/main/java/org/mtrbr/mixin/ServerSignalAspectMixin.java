package org.mtrbr.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.mtr.mapping.holder.World;
import org.mtr.mod.block.BlockSignalBase;
import org.mtrbr.server.ServerAspect;
import org.mtrbr.server.ServerAspectManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the server authorization result only for explicitly bound SignalFaces. */
@Mixin(BlockSignalBase.BlockEntityBase.class)
public abstract class ServerSignalAspectMixin {
	@Inject(method = "getActualAspect", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtrbr$serverAspect(boolean occupied, boolean isBackSide, CallbackInfoReturnable<Integer> callback) {
		final BlockSignalBase.BlockEntityBase self = (BlockSignalBase.BlockEntityBase) (Object) this;
		final World world = self.getWorld2();
		if (world != null && world.data instanceof ServerLevel level) {
			final org.mtr.mapping.holder.BlockPos pos = self.getPos2();
			final ServerAspect aspect = ServerAspectManager.get(level, new BlockPos(pos.getX(), pos.getY(), pos.getZ()), isBackSide);
			if (aspect != null) {
				callback.setReturnValue(aspect.getValue());
			}
		}
	}
}
