package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.mtr.mod.block.BlockTrainPoweredSensorBase;
import org.mtr.mapping.holder.Property;
import org.mtrbr.data.SensorConfig;
import org.mtrbr.data.SensorSavedData;
import org.mtr.libraries.com.google.gson.*;
import java.util.*;

public final class SensorManager {
    private static final Map<ServerLevel, Map<BlockPos, SensorOutputState>> OUTPUTS = new WeakHashMap<>();
    private static volatile String published = "[]";
    private SensorManager() { }
    public static boolean isSensor(net.minecraft.world.level.block.state.BlockState state) {
        return ResourceLocation.fromNamespaceAndPath("mtr", "train_sensor").equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }
    public static boolean advanced(ServerLevel level, BlockPos pos) {
        SensorConfig c = SensorSavedData.get(level).sensors.get(pos);
        return c != null && c.enabled();
    }
    public static String id(ServerLevel level, BlockPos pos) { return level.dimension().location() + "@" + pos.asLong(); }
    public static String published() { return published; }
    public static void tick(net.minecraft.server.MinecraftServer server) {
        JsonArray all = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            SensorSavedData data = SensorSavedData.get(level);
            var simulator = SectionStateManager.getSimulator(level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath());
            var authorizations = simulator == null ? List.<RouteRequestManager.AuthorizedPath>of() : RouteRequestManager.getAuthorizedPaths(simulator);
            var vehicles = simulator == null ? List.<RouteRequestManager.VehicleSnapshot>of() : RouteRequestManager.getVehicleSnapshots(simulator);
            Map<Long, RouteRequestManager.VehicleSnapshot> byVehicle = new HashMap<>();
            vehicles.forEach(v -> byVehicle.put(v.vehicleId(), v));
            Map<Long, List<SensorRule.Segment>> paths = new HashMap<>();
            for (var auth : authorizations) {
                var traversals = auth.path().getTraversals();
                List<SensorRule.Segment> segments = new ArrayList<>();
                for (int i = 0; i < traversals.size(); i++) {
                    var t = traversals.get(i);
                    boolean reversal = i > 0 && traversals.get(i - 1).startNode().equals(t.endNode()) && traversals.get(i - 1).endNode().equals(t.startNode());
                    segments.add(new SensorRule.Segment(t.sectionId(), t.startDistance(), t.endDistance(), reversal));
                }
                paths.put(auth.vehicleId(), segments);
            }
            Map<BlockPos, SensorOutputState> outputs = OUTPUTS.computeIfAbsent(level, l -> new HashMap<>());
            outputs.keySet().retainAll(data.sensors.keySet());
            for (var entry : new ArrayList<>(data.sensors.entrySet())) {
                BlockPos pos = entry.getKey(); SensorConfig config = entry.getValue();
                boolean loaded = level.hasChunkAt(pos);
                if (loaded && !isSensor(level.getBlockState(pos))) { data.remove(pos); outputs.remove(pos); continue; }
                boolean condition = false;
                if (config.enabled()) for (var auth : authorizations) {
                    var vehicle = byVehicle.get(auth.vehicleId());
                    if (vehicle == null || vehicle.path() == null || !vehicle.path().getFingerprint().equals(auth.path().getFingerprint())) continue;
                    if (SensorRule.matches(config, paths.get(auth.vehicleId()), vehicle.head(), auth.startDistance(), auth.endDistance())) { condition = true; break; }
                }
                SensorOutputState output = outputs.computeIfAbsent(pos, p -> new SensorOutputState());
                boolean powered = output.update(config.enabled(), config.pulse(), config.pulseTicks(), condition, level.getGameTime());
                if (loaded && !config.enabled()) powered = org.mtr.mod.block.IBlock.getStatePropertySafe(new org.mtr.mapping.holder.BlockState(level.getBlockState(pos)), BlockTrainPoweredSensorBase.POWERED) > 0;
                if (loaded && config.enabled()) {
                    var state = new org.mtr.mapping.holder.BlockState(level.getBlockState(pos));
                    var updated = state.with(new Property<>(BlockTrainPoweredSensorBase.POWERED.data), powered ? 2 : 0);
                    if (!updated.data.equals(state.data)) level.setBlock(pos, updated.data, 3);
                }
                JsonObject item = JsonParser.parseString(config.json()).getAsJsonObject();
                item.addProperty("id", id(level, pos)); item.addProperty("dimension", level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath());
                item.addProperty("x", pos.getX()); item.addProperty("y", pos.getY()); item.addProperty("z", pos.getZ());
                item.addProperty("loaded", loaded); item.addProperty("powered", powered); item.addProperty("condition", condition);
                all.add(item);
            }
        }
        published = all.toString();
    }
    public static void reset(ServerLevel level, BlockPos pos) {
        OUTPUTS.computeIfAbsent(level, l -> new HashMap<>()).remove(pos);
        if (level.hasChunkAt(pos) && isSensor(level.getBlockState(pos))) {
            var state = new org.mtr.mapping.holder.BlockState(level.getBlockState(pos));
            level.setBlock(pos, state.with(new Property<>(BlockTrainPoweredSensorBase.POWERED.data), 0).data, 3);
        }
    }
}
