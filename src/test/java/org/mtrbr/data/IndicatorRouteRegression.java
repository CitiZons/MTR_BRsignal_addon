package org.mtrbr.data;

import org.mtrbr.block.ColorLightRoute;

/** Route binding validation used by both the game input and server packet handler. */
public final class IndicatorRouteRegression {
    public static void main(String[] args) {
        for (int route = 1; route <= 6; route++) {
            final String expected = "route=" + route;
            require(expected.equals(RouteContent.validate("  ROUTE=" + route + "  ")), expected);
            final ColorLightRoute state = ColorLightRoute.fromRouteContent(expected);
            require(state != ColorLightRoute.OFF && state.getSerializedName().equals("" + route), "state " + route);
        }
        for (String invalid : new String[] {null, "", "route=0", "route=7", "route=-1", "route=33", "route=1-6", "route=3x"}) {
            require(RouteContent.validate(invalid) == null, "reject " + invalid);
            require(ColorLightRoute.fromRouteContent(invalid) == ColorLightRoute.OFF, "off " + invalid);
        }
        for (String path : new String[] {"path=0", "path=20", "path=A", "path=UF", "path=adl"}) {
            require(path.equals(RouteContent.validate(path)), "preserve " + path);
            require(ColorLightRoute.fromRouteContent(path) == ColorLightRoute.OFF, "path not route");
        }
        System.out.println("PASS: indicator routes 1-6, invalid routes fail closed, LED path inputs preserved");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
