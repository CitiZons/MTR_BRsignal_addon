package org.mtrbr.server;

import org.mtrbr.data.SensorConfig;
import java.util.List;

public final class SensorRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var c = new SensorConfig("Crossing", true, false, 20, List.of("A", "T"), List.of("T"));
        var a = new SensorRule.Segment("A", 0, 100, false);
        var target = new SensorRule.Segment("T", 100, 200, false);
        check(SensorRule.matches(c, List.of(a, target), 10, 10, 50), "Target need not be authorized yet");
        check(!SensorRule.matches(c, List.of(a, new SensorRule.Segment("BRANCH", 100, 200, false)), 10, 10, 50), "Branch must not trigger");
        check(!SensorRule.matches(c, List.of(a, target), 201, 0, 300), "Passed target must not trigger");
        check(!SensorRule.matches(c, List.of(a, new SensorRule.Segment("T", 100, 200, true)), 10, 10, 50), "Future reverse visit must not trigger");
        check(!SensorRule.matches(c, List.of(new SensorRule.Segment("X", 0, 100, false), new SensorRule.Segment("A", 100, 200, true), new SensorRule.Segment("T", 200, 300, false)), 10, 10, 250), "Approach after reversal must not trigger");
        var empty = new SensorConfig("", true, false, 20, List.of("A"), List.of());
        check(SensorRule.matches(empty, List.of(a), 10, 10, 50), "Empty target skips filter");
        check(!SensorRule.matches(empty, List.of(a), 10, 10, 10), "No live forward authority");
        check(!SensorRule.matches(empty, List.of(a), 110, 0, 200), "Historical approach is not current authority");
        check(SensorConfig.parse(c.json()).equals(c), "JSON roundtrip");
        try { new SensorConfig("", true, false, 20, List.of("A"), List.of("T")); throw new AssertionError("Subset check missing"); }
        catch (IllegalArgumentException expected) { }
        var output = new SensorOutputState();
        check(output.update(true, true, 3, true, 10), "Rising pulse");
        check(output.update(true, true, 3, true, 12), "Pulse duration");
        check(!output.update(true, true, 3, true, 13), "No repeated pulse during same condition");
        check(!output.update(true, true, 3, false, 14), "Rearm");
        check(output.update(true, true, 3, true, 15), "Second rising edge");
        check(!output.update(false, true, 3, true, 16), "Disable clears pulse");
        check(output.update(true, false, 3, true, 17), "Continuous on");
        check(!output.update(true, false, 3, false, 18), "Continuous off");
        System.out.println("Sensor regression passed");
    }
}
