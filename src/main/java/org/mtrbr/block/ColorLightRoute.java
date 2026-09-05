package org.mtrbr.block;

import net.minecraft.util.StringRepresentable;

/** Static colour-light model selected by the currently authorized route. */
public enum ColorLightRoute implements StringRepresentable {
	OFF("off"),
	ROUTE_1("1"),
	ROUTE_2("2"),
	ROUTE_3("3"),
	ROUTE_4("4"),
	ROUTE_5("5"),
	ROUTE_6("6");

	private final String name;

	ColorLightRoute(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	public static ColorLightRoute fromRouteContent(String content) {
		if (content == null) return OFF;
		return switch (content.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "route=1" -> ROUTE_1;
			case "route=2" -> ROUTE_2;
			case "route=3" -> ROUTE_3;
			case "route=4" -> ROUTE_4;
			case "route=5" -> ROUTE_5;
			case "route=6" -> ROUTE_6;
			default -> OFF;
		};
	}
}
