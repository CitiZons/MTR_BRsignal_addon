package org.mtrbr.server;

/** Aspect values follow the existing addon renderer: green, red, yellow, double yellow. */
public enum ServerAspect {
	GREEN(0),
	RED(1),
	YELLOW(2),
	DOUBLE_YELLOW(3);

	private final int value;

	ServerAspect(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}
}
