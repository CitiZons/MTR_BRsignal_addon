package org.mtrbr.data;

public final class SpeedSignTextRegression {
    public static void main(String[] args) {
        require(SpeedSignText.validate("50", "", false).equals(new SpeedSignText("50", "")), "single speed");
        require(SpeedSignText.validate("50", "stale", false).lower().isEmpty(), "single ignores unused second row");
        var differential = SpeedSignText.validate("30", "55", true);
        require(differential != null && differential.hasDivider(), "numeric differential divider");
        for (String code : new String[]{"HST", "LH", "MU", "DMU", "EMU", "SP", "WES", "LUL", "CS", "S7"}) {
            var train = SpeedSignText.validate(code, "40", true);
            require(train != null && train.isTrainClass() && !train.hasDivider(), "train class " + code);
        }
        require(SpeedSignText.validate(" dmu ", " 40 ", true).upper().equals("DMU"), "normalization");
        for (String[] input : new String[][]{{"55","55"},{"60","55"},{"0","55"},{"30","0"},{"1000","1001"},
                {"","55"},{"30",""},{"-1","55"},{"30.5","55"},{"03","55"},{"30","DMU"},{"ABCDE","55"},
                {"调车","55"},{"A/B","55"},{"<>","55"}}) {
            require(SpeedSignText.validate(input[0], input[1], true) == null, "reject " + input[0] + "/" + input[1]);
        }
        require(SpeedSignText.validate("DMU", "", false) == null, "single is numeric");
        require(SpeedSignText.validate(null, "", false) == null, "null input");
        mounts();
        System.out.println("Speed sign text and mounting regression passed");
    }

    private static void mounts() {
        var bottom = org.mtrbr.block.SpeedSignMount.BOTTOM;
        var center = org.mtrbr.block.SpeedSignMount.CENTER;
        var top = org.mtrbr.block.SpeedSignMount.TOP;
        require(bottom.bottom(false) == 0 && center.bottom(false) == .125F && top.bottom(false) == .25F, "three speed plate positions");
        require(bottom.bottom(true) == 0 && top.bottom(true) == .75F, "two arrow positions");
        require(!center.allows(true) && center.allows(false), "center is exclusive to speed plates");
        for (boolean arrow : new boolean[]{false, true}) for (var mount : org.mtrbr.block.SpeedSignMount.values()) {
            if (!mount.allows(arrow)) continue;
            float plateHeight = arrow ? .25F : .75F;
            float min = mount.bottom(arrow), max = min + plateHeight;
            require(min >= 0 && max <= 1, "plate remains in block");
            require(mount.offset(arrow) + (arrow ? 0 : .25F) == min, "render offset matches selection height");
            require(mount.poleHeight(arrow) > min, "pole reaches plate");
            if (mount == top) require(mount.poleHeight(arrow) == 1, "all top mounts have full height poles");
            else require(mount.poleHeight(arrow) < max, "other poles stop below plate top");
        }
        require(top.bottom(true) + .25F == 1 + bottom.bottom(false), "lower arrow touches upper speed plate");
        require(top.bottom(false) + .75F == 1 + bottom.bottom(true), "upper arrow touches lower speed plate");
    }
    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
