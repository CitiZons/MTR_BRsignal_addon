package org.mtrbr.logic;

/** Main signal's front aspect; missing authority must never indicate clear. */
public enum RepeatingSignalDisplay {
    ON("on"), OFF("off"), OFF_LIMITING("off_limiting");
    private final String textureName;
    RepeatingSignalDisplay(String textureName) { this.textureName = textureName; }
    public String textureName() { return textureName; }
    public static RepeatingSignalDisplay forBinding(boolean bound, Integer aspect) {
        return bound ? fromAspect(aspect) : ON;
    }
    public static RepeatingSignalDisplay fromAspect(Integer aspect) {
        if (aspect == null) return OFF_LIMITING;
        return switch (aspect) { case 1 -> ON; case 0 -> OFF; default -> OFF_LIMITING; };
    }
}
