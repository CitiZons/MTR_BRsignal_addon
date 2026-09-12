package org.mtrbr.block;

import net.minecraft.util.StringRepresentable;

public enum TextSignPoleMount implements StringRepresentable {
    HANGING, CENTER, BOTTOM;

    @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
