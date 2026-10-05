package org.mtrbr.web;

import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtrbr.server.SensorManager;
import org.mtrbr.server.SectionStateManager;
import org.mtrbr.data.*;
import net.minecraft.core.BlockPos;
import java.util.concurrent.TimeUnit;

public final class SensorWebService {
    private SensorWebService() { }
    public static JsonObject save(String token, String device, JsonObject request) {
        JsonObject result = new JsonObject();
        var server = WebTopologySnapshot.server();
        if (server == null) { result.addProperty("ok", false); result.addProperty("reason", "SERVER_NOT_READY"); return result; }
        try {
            return server.submit(() -> {
                if (!WebSessionManager.access(server, token, device).canDispatch()) throw new IllegalArgumentException("READ_ONLY");
                String id = request.get("id").getAsString(); int split = id.lastIndexOf('@');
                String dimension = id.substring(0, split); BlockPos pos = BlockPos.of(Long.parseLong(id.substring(split + 1)));
                var level = java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false).filter(l -> l.dimension().location().toString().equals(dimension)).findFirst().orElseThrow();
                var data = SensorSavedData.get(level);
                if (!data.sensors.containsKey(pos) || level.hasChunkAt(pos) && !SensorManager.isSensor(level.getBlockState(pos))) throw new IllegalArgumentException("SENSOR_NOT_FOUND");
                SensorConfig c = SensorConfig.parse(request.get("config").toString());
                if (!request.has("expected") || !data.sensors.get(pos).equals(SensorConfig.parse(request.get("expected").toString())))
                    throw new IllegalArgumentException("CONFIG_CHANGED_REOPEN_SENSOR");
                String dim = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
                var simulator = SectionStateManager.getSimulator(dim);
                var sections = simulator == null ? java.util.Map.<String, SectionStateManager.SectionSnapshot>of() : SectionStateManager.getPublishedSections(simulator);
                if (c.approach().stream().anyMatch(s -> !sections.containsKey(s) || !sections.get(s).exists)) throw new IllegalArgumentException("SECTION_NOT_FOUND");
                data.put(pos, c); SensorManager.reset(level, pos);
                org.mtrbr.server.MtrbrDebugLog.event("SENSOR-SAVE", "sensor=" + id + " actor=" + WebSessionManager.operator(server, token));
                JsonObject ok = new JsonObject(); ok.addProperty("ok", true); return ok;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            result.addProperty("ok", false); result.addProperty("reason", e.getCause() == null ? "INVALID_REQUEST" : e.getCause().getMessage()); return result;
        }
    }
}
