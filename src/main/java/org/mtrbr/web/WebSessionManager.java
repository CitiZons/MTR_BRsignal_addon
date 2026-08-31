package org.mtrbr.web;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges an authenticated in-game operator to a short-lived browser session. */
public final class WebSessionManager {
	private static final long SESSION_LIFETIME_MILLIS = 8 * 60 * 60 * 1000L;
	private static final long ACTIVE_WINDOW_MILLIS = 5_000L;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

	private WebSessionManager() {
	}

	public static String issue(ServerPlayer player) {
		final byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		SESSIONS.put(token, new Session(player.getUUID(), System.currentTimeMillis() + SESSION_LIFETIME_MILLIS, 0));
		return token;
	}

	public static boolean touchAndCanDispatch(MinecraftServer server, String token) {
		if (token == null || token.isBlank()) return false;
		final Session session = SESSIONS.get(token);
		final long now = System.currentTimeMillis();
		if (session == null || session.expiresAt < now) {
			SESSIONS.remove(token);
			return false;
		}
		final ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
		if (player == null || !player.hasPermissions(2)) return false;
		SESSIONS.put(token, new Session(session.playerId, session.expiresAt, now));
		return true;
	}

	public static boolean isDispatching(UUID playerId) {
		final long now = System.currentTimeMillis();
		return SESSIONS.values().stream().anyMatch(session -> session.playerId.equals(playerId) && session.lastSeenAt >= now - ACTIVE_WINDOW_MILLIS && session.expiresAt >= now);
	}

	public static void reset() {
		SESSIONS.clear();
	}

	private record Session(UUID playerId, long expiresAt, long lastSeenAt) {
	}
}
