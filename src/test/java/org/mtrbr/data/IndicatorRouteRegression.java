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
        final String compound = "route=2 || path=1 || shunt=yard_1";
        require(compound.equals(RouteContent.validate(" SHUNT=Yard_1||path=01 || ROUTE=2 ")), "canonical compound");
        require(RouteContent.isShunt(compound), "compound selects shunt");
        require("2".equals(ColorLightRoute.fromRouteContent(compound).getSerializedName()), "compound colour route");
        require("path=1".equals(RouteContent.part(compound, "path")), "compound LED path");
        require(ContentTextureRegistry.getTexture(compound).equals(ContentTextureRegistry.getTexture("path=1")), "compound LED texture");
        require(ContentTextureRegistry.getColorLightTexture(compound).equals(ContentTextureRegistry.getColorLightTexture("route=2")), "compound colour texture");
        require("path=NULL".equals(RouteContent.part("path=NULL || shunt=yard", "path")), "preserve unlit LED sentinel");
        for (String invalid : new String[] {"route=1 || route=2", "shunt=a || shunt=b", "path=1 || path=2",
                "path=1 ||", "|| shunt=yard", "route=2 || shunt=a b", "route=1 | shunt=a", "route=1 || unknown=a"}) {
            require(RouteContent.validate(invalid) == null, "reject compound " + invalid);
            require(!RouteContent.isShunt(invalid), "invalid compound cannot authorize shunt");
            require(ColorLightRoute.fromRouteContent(invalid) == ColorLightRoute.OFF, "invalid compound colour off");
        }
        final String longest = "route=6 || path=ABCD || shunt=" + "x".repeat(32);
        require(longest.length() <= RouteContent.MAX_LENGTH && longest.equals(RouteContent.validate(longest)), "maximum compound round trip");
        final var binding = new RouteBinding(new net.minecraft.core.BlockPos(20, -60, 30), longest);
        require(binding.equals(RouteBinding.fromTag(binding.toTag())), "compound NBT round trip");
        final var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        final var encoded = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            org.mtrbr.network.SetRouteBindingPacket.encode(new org.mtrbr.network.SetRouteBindingPacket(net.minecraft.core.BlockPos.ZERO, binding.node(), longest), buffer);
            org.mtrbr.network.SetRouteBindingPacket.encode(org.mtrbr.network.SetRouteBindingPacket.decode(buffer), encoded);
            require(encoded.readBlockPos().equals(net.minecraft.core.BlockPos.ZERO), "packet signal");
            require(encoded.readBlockPos().equals(binding.node()) && encoded.readUtf(64).equals(longest), "packet preserves longest compound");
        } finally {
            buffer.release();
            encoded.release();
        }
        System.out.println("PASS: indicator routes, compound shunt/indicator bindings, persistence and packet round trips");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
