package org.mtrbr.event;

import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.mtrbr.server.SensorManager;
import org.mtrbr.data.SensorSavedData;

@Mod.EventBusSubscriber(modid = "mtr_brsignal_addon")
public final class SensorEvents {
    @SubscribeEvent public static void placed(BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel() instanceof ServerLevel level && SensorManager.isSensor(e.getPlacedBlock())) SensorSavedData.get(level).register(e.getPos());
    }
    @SubscribeEvent public static void broken(BlockEvent.BreakEvent e) {
        if (e.getLevel() instanceof ServerLevel level && SensorManager.isSensor(e.getState())) SensorSavedData.get(level).remove(e.getPos());
    }
    @SubscribeEvent public static void loaded(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk)
            for (var entity : chunk.getBlockEntities().values()) if (SensorManager.isSensor(entity.getBlockState())) SensorSavedData.get(level).register(entity.getBlockPos());
    }
}
