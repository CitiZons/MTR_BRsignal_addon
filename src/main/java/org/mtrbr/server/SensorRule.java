package org.mtrbr.server;

import org.mtrbr.data.SensorConfig;
import java.util.List;

public final class SensorRule {
    private SensorRule() { }
    public record Segment(String id, double start, double end, boolean reversal) { }
    /** Match one forward occurrence, stopping at the next native reversal. */
    public static boolean matches(SensorConfig config, List<Segment> path, double head, double authorityStart, double authorityEnd) {
        if (!config.enabled() || authorityEnd <= head || config.approach().isEmpty()) return false;
        for (int i = 0; i < path.size(); i++) {
            Segment entry = path.get(i);
            if (entry.reversal && entry.start > head) break;
            if (entry.end <= Math.max(head, authorityStart) || entry.start >= authorityEnd || !config.approach().contains(entry.id)) continue;
            if (config.targets().isEmpty()) return true;
            for (int j = i; j < path.size(); j++) {
                Segment target = path.get(j);
                if (j > i && target.reversal) break;
                if (target.end > head && config.targets().contains(target.id)) return true;
            }
        }
        return false;
    }
}
