package org.mtrbr.data;

import java.util.Locale;

/** Shared by the editor and the server, independent of signalling logic. */
public record SpeedSignText(String upper, String lower) {
    public static final int MAX_LENGTH = 4;

    public static SpeedSignText validate(String upper, String lower, boolean doubleLine) {
        if (upper == null || lower == null) return null;
        upper = upper.trim().toUpperCase(Locale.ROOT);
        lower = lower.trim();
        if (!doubleLine) return speed(upper) ? new SpeedSignText(upper, "") : null;
        if (!speed(lower)) return null;
        // S7 is a train class in the supplied reference, alongside DMU, HST, etc.
        if (upper.matches("[A-Z][A-Z0-9]{0,3}")) return new SpeedSignText(upper, lower);
        if (!speed(upper) || Integer.parseInt(upper) >= Integer.parseInt(lower)) return null;
        return new SpeedSignText(upper, lower);
    }

    private static boolean speed(String value) {
        return value.matches("[1-9][0-9]{0,2}");
    }

    public boolean hasDivider() { return !lower.isEmpty() && upper.matches("[0-9]+"); }
    public boolean isTrainClass() { return !lower.isEmpty() && !hasDivider(); }
}
