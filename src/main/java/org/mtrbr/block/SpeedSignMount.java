package org.mtrbr.block;

/** Vertical installation independent of block registration and client rendering. */
public enum SpeedSignMount {
    BOTTOM, CENTER, TOP;

    public boolean allows(boolean arrow) { return !arrow || this != CENTER; }

    public float bottom(boolean arrow) {
        float gap = arrow ? .75F : .25F;
        return switch (this) { case BOTTOM -> 0; case CENTER -> gap / 2; case TOP -> gap; };
    }

    public float offset(boolean arrow) { return bottom(arrow) - (arrow ? 0 : .25F); }

    public float poleHeight(boolean arrow) {
        return this == TOP ? 1 : bottom(arrow) + (arrow ? .125F : .5625F);
    }
}
