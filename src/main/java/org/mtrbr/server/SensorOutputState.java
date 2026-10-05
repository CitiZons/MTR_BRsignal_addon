package org.mtrbr.server;

public final class SensorOutputState {
    private boolean previous;
    private long until;
    public boolean update(boolean enabled, boolean pulse, int pulseTicks, boolean condition, long tick) {
        if (!enabled) { previous = false; until = 0; return false; }
        if (pulse && condition && !previous) until = tick + pulseTicks;
        previous = condition;
        return pulse ? tick < until : condition;
    }
}
