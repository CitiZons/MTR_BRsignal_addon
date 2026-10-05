package org.mtrbr.mixin;

import org.mtr.mod.block.BlockTrainPoweredSensorBase;
import org.mtr.mapping.holder.*;
import org.mtrbr.server.SensorManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockTrainPoweredSensorBase.class)
public abstract class TrainSensorPowerMixin {
    @Inject(method = "power", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtrbr$power(World world, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (world.data instanceof ServerLevel level && SensorManager.advanced(level, pos.data)) ci.cancel();
    }
    @Inject(method = "scheduledTick2", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtrbr$tick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (SensorManager.advanced(world.data, pos.data)) ci.cancel();
    }
}
