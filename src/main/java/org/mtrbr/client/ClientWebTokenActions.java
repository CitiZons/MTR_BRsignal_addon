package org.mtrbr.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import java.net.URI;
import java.util.UUID;

public final class ClientWebTokenActions {
	private static UUID pendingRequest;
	private static long requestedAt;
    private static String sensorId;

	private ClientWebTokenActions() {}

	public static void generateAndOpen() {
        sensorId = null;
		pendingRequest = UUID.randomUUID();
		requestedAt = Util.getMillis();
		command("generate open " + pendingRequest);
	}
    public static void generateAndOpenSensor(String id) {
        sensorId = id;
        pendingRequest = UUID.randomUUID(); requestedAt = Util.getMillis();
        command("open_sensor " + pendingRequest);
    }

	public static void command(String action) {
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getConnection() == null) return;
		minecraft.setScreen(new ChatScreen(""));
		minecraft.getConnection().sendCommand("mtrbr web_token " + action);
	}

	public static void open(UUID requestId, String url) {
		if (!requestId.equals(pendingRequest) || Util.getMillis() - requestedAt > 30_000) return;
		pendingRequest = null;
		try {
			final URI uri = URI.create(url + (sensorId == null ? "" : "&sensor=" + java.net.URLEncoder.encode(sensorId, java.nio.charset.StandardCharsets.UTF_8)));
			if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
				Util.getPlatform().openUri(uri);
			}
		} catch (IllegalArgumentException ignored) {
			// The server also sends a private chat link, which remains available on failure.
		}
	}
}
