package org.mtrbr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashMap;
import java.util.Map;

public final class SensorSavedData extends SavedData {
    public final Map<BlockPos, SensorConfig> sensors = new HashMap<>();
    public static SensorSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SensorSavedData::load, SensorSavedData::new, "mtrbr_sensors");
    }
    private static SensorSavedData load(CompoundTag tag) {
        SensorSavedData data = new SensorSavedData();
        for (String key : tag.getAllKeys()) try {
            data.sensors.put(BlockPos.of(Long.parseLong(key)), SensorConfig.parse(tag.getString(key)));
        } catch (RuntimeException ignored) { }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        sensors.forEach((pos, config) -> tag.putString(Long.toString(pos.asLong()), config.json()));
        return tag;
    }
    public void register(BlockPos pos) {
        if (!sensors.containsKey(pos)) { sensors.put(pos.immutable(), SensorConfig.defaults()); setDirty(); }
    }
    public void put(BlockPos pos, SensorConfig config) { sensors.put(pos.immutable(), config); setDirty(); }
    public void remove(BlockPos pos) { if (sensors.remove(pos) != null) setDirty(); }
}
