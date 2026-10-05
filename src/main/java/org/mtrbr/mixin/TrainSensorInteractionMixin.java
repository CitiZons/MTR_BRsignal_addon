package org.mtrbr.mixin;

import org.mtr.mod.block.BlockTrainSensorBase;
import org.mtr.mapping.holder.*;
import org.mtrbr.server.SensorManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockTrainSensorBase.class)
public abstract class TrainSensorInteractionMixin {
    @Inject(method = "onUse2", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtrbr$use(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
                           BlockHitResult hit, CallbackInfoReturnable<ActionResult> ci) {
        if (SensorManager.isSensor(state.data)) ci.setReturnValue(ActionResult.getPassMapped());
    }
}
