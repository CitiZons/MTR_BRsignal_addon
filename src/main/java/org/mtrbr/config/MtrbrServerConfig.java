package org.mtrbr.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server-owned public endpoint settings for the MTR-hosted Web UI. */
public final class MtrbrServerConfig {
	public static final ForgeConfigSpec SPEC;
	public static final ForgeConfigSpec.ConfigValue<String> WEB_PUBLIC_HOST;

	static {
		final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
		WEB_PUBLIC_HOST = builder.comment("Public hostname used in web_token URLs on dedicated servers. Leave blank to disable token URL generation.")
				.define("web_public_host", "");
		SPEC = builder.build();
	}

	private MtrbrServerConfig() {
	}

	public static String webPublicHost() {
		return WEB_PUBLIC_HOST.get().trim();
	}
}
