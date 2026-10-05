package org.mtrbr.data;

import java.util.List;
import org.mtr.libraries.com.google.gson.Gson;

public record SensorConfig(String name, boolean enabled, boolean pulse, int pulseTicks,
                           List<String> approach, List<String> targets) {
    private static final Gson GSON = new Gson();
    public SensorConfig {
        if (name == null || name.length() > 40 || pulseTicks < 1 || pulseTicks > 1200
                || approach == null || targets == null || approach.size() > 4096 || targets.size() > 4096)
            throw new IllegalArgumentException("Invalid sensor configuration");
        approach = approach.stream().distinct().toList();
        targets = targets.stream().distinct().toList();
        if (approach.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 128)
                || !approach.containsAll(targets)) throw new IllegalArgumentException("Targets must be a subset of Approach Sections");
    }
    public static SensorConfig defaults() { return new SensorConfig("", false, false, 20, List.of(), List.of()); }
    public String json() { return GSON.toJson(this); }
    public static SensorConfig parse(String json) {
        SensorConfig c = GSON.fromJson(json, SensorConfig.class);
        if (c == null) throw new IllegalArgumentException("Missing configuration");
        return new SensorConfig(c.name, c.enabled, c.pulse, c.pulseTicks, c.approach, c.targets);
    }
}
