package org.mtrbr.server;

/** Simulation-thread state for the native reversal / new activity handoff. */
final class TurnbackHandoff {
    /** Doors and dwell belong to native termination, before the reacquisition clock starts. */
    private static final long NATIVE_TERMINATION_TIMEOUT_TICKS = 1200;
    private static final long REACQUIRE_TIMEOUT_TICKS = 200;

    enum Phase { NORMAL, NATIVE_TERMINATING, REACQUIRING, FAILED }

    private Phase phase = Phase.NORMAL;
    private long startedTick = -1;
    private long reversalTick = -1;
    private boolean reacquirePending;

    Phase phase() { return phase; }
    boolean active() { return phase == Phase.NATIVE_TERMINATING || phase == Phase.REACQUIRING; }
    boolean awaitingActivity() { return phase == Phase.REACQUIRING; }
    boolean reacquirePending() { return reacquirePending; }

    boolean begin(long tick) {
        if (phase != Phase.NORMAL) return false;
        phase = Phase.NATIVE_TERMINATING;
        startedTick = tick;
        return true;
    }

    void reversalObserved(long tick) {
        phase = Phase.REACQUIRING;
        reversalTick = tick;
        reacquirePending = true;
    }

    void activityReady() {
        phase = Phase.NORMAL;
        startedTick = -1;
        reversalTick = -1;
    }

    void grantReported() {
        reacquirePending = false;
    }

    void fail() {
        phase = Phase.FAILED;
    }

    /** Returns the failed phase once; a failed handoff must no longer defer the movement gate. */
    Timeout checkTimeout(long tick) {
        long elapsed;
        if (phase == Phase.NATIVE_TERMINATING) {
            elapsed = tick - startedTick;
            if (elapsed < NATIVE_TERMINATION_TIMEOUT_TICKS) return null;
        } else if (phase == Phase.REACQUIRING && reversalTick >= 0) {
            elapsed = tick - reversalTick;
            if (elapsed < REACQUIRE_TIMEOUT_TICKS) return null;
        } else {
            return null;
        }
        Timeout timeout = new Timeout(phase, elapsed);
        fail();
        return timeout;
    }

    record Timeout(Phase phase, long elapsedTicks) {}
}
