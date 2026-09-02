package org.mtrbr.web;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/** Bridges an authenticated in-game operator to a short-lived browser session. */
public final class WebSessionManager {
	private static final long SESSION_LIFETIME_MILLIS = 8 * 60 * 60 * 1000L;
	private static final long ACTIVE_WINDOW_MILLIS = 5_000L;
	private static final int MAX_TOKENS_PER_OPERATOR = 5;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Object LOCK = new Object();
	private static final Map<String, Session> SESSIONS = new HashMap<>();
	private static final Map<UUID, List<String>> TOKENS_BY_OPERATOR = new HashMap<>();

	private WebSessionManager() {
	}

	public static IssueResult issue(ServerPlayer player) {
		synchronized (LOCK) {
			final long now = System.currentTimeMillis();
			expireTokens(now);
			final List<String> tokens = TOKENS_BY_OPERATOR.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>());
			if (tokens.size() >= MAX_TOKENS_PER_OPERATOR) return new IssueResult("");
			final byte[] bytes = new byte[24];
			RANDOM.nextBytes(bytes);
			final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			SESSIONS.put(token, new Session(player.getUUID(), now + SESSION_LIFETIME_MILLIS, "", Status.ACTIVE, 0));
			tokens.add(token);
			return new IssueResult(token);
		}
	}

	public static SessionView access(MinecraftServer server, String token, String deviceId) {
		synchronized (LOCK) {
			if (token == null || token.isBlank()) return SessionView.none();
			final Session session = SESSIONS.get(token);
			if (session == null) return SessionView.none();
			final long now = System.currentTimeMillis();
			Session current = session;
			if (current.status == Status.ACTIVE && current.expiresAt < now) {
				current = transition(token, current, Status.EXPIRED);
			}
			if (current.status == Status.ACTIVE) {
				final ServerPlayer player = server.getPlayerList().getPlayer(current.playerId);
				if (player == null) {
					current = transition(token, current, Status.PLAYER_OFFLINE);
				} else if (!player.hasPermissions(2)) {
					return new SessionView(false, "");
				} else if (deviceId == null || deviceId.isBlank()) {
					return new SessionView(false, "");
				} else if (current.deviceId.isBlank()) {
					current = new Session(current.playerId, current.expiresAt, deviceId, Status.ACTIVE, now);
					SESSIONS.put(token, current);
				} else if (!current.deviceId.equals(deviceId)) {
					current = transition(token, current, Status.LEAKED);
				} else {
					current = new Session(current.playerId, current.expiresAt, current.deviceId, Status.ACTIVE, now);
					SESSIONS.put(token, current);
				}
			}
			return current.status == Status.ACTIVE ? new SessionView(true, "") : new SessionView(false, current.status.apiReason);
		}
	}

	public static boolean isDispatching(UUID playerId) {
		synchronized (LOCK) {
			final long now = System.currentTimeMillis();
			return SESSIONS.values().stream().anyMatch(session -> session.playerId.equals(playerId) && session.status == Status.ACTIVE && session.lastSeenAt >= now - ACTIVE_WINDOW_MILLIS && session.expiresAt >= now);
		}
	}

	/** Returns an audit-safe operator identity after a request has been authenticated. */
	public static String operator(MinecraftServer server, String token) {
		synchronized (LOCK) {
			final Session session = SESSIONS.get(token);
			if (session == null) return "<unknown>";
			final ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
			return player == null ? session.playerId.toString() : player.getGameProfile().getName();
		}
	}

	public static List<TokenView> list(UUID playerId) {
		synchronized (LOCK) {
			expireTokens(System.currentTimeMillis());
			return TOKENS_BY_OPERATOR.getOrDefault(playerId, List.of()).stream()
					.map(token -> new TokenView(token, SESSIONS.get(token).status)).toList();
		}
	}

	public static boolean revoke(UUID playerId, int number) {
		synchronized (LOCK) {
			expireTokens(System.currentTimeMillis());
			final List<String> tokens = TOKENS_BY_OPERATOR.get(playerId);
			if (tokens == null || number < 1 || number > tokens.size()) return false;
			final String token = tokens.remove(number - 1);
			final Session session = SESSIONS.get(token);
			if (session != null) transition(token, session, Status.REVOKED);
			if (tokens.isEmpty()) TOKENS_BY_OPERATOR.remove(playerId);
			return true;
		}
	}

	public static void invalidateForOfflinePlayer(UUID playerId) {
		synchronized (LOCK) {
			for (final String token : List.copyOf(TOKENS_BY_OPERATOR.getOrDefault(playerId, List.of()))) {
				final Session session = SESSIONS.get(token);
				if (session != null && session.status == Status.ACTIVE) transition(token, session, Status.PLAYER_OFFLINE);
			}
		}
	}

	public static void reset() {
		synchronized (LOCK) {
			SESSIONS.clear();
			TOKENS_BY_OPERATOR.clear();
		}
	}

	private static Session transition(String token, Session session, Status status) {
		final Session next = new Session(session.playerId, session.expiresAt, session.deviceId, status, session.lastSeenAt);
		SESSIONS.put(token, next);
		return next;
	}

	private static void expireTokens(long now) {
		for (final Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
			final Session session = entry.getValue();
			if (session.status == Status.ACTIVE && session.expiresAt < now) transition(entry.getKey(), session, Status.EXPIRED);
		}
		TOKENS_BY_OPERATOR.values().forEach(tokens -> tokens.removeIf(token -> SESSIONS.get(token).status == Status.EXPIRED));
		TOKENS_BY_OPERATOR.entrySet().removeIf(entry -> entry.getValue().isEmpty());
	}

	public record IssueResult(String token) {
		public boolean issued() { return !token.isBlank(); }
	}

	public record SessionView(boolean canDispatch, String invalidationReason) {
		private static SessionView none() { return new SessionView(false, ""); }
	}

	public record TokenView(String token, Status status) {
	}

	public enum Status {
		ACTIVE(""), LEAKED("LEAKED"), PLAYER_OFFLINE("PLAYER_OFFLINE"), REVOKED("REVOKED"), EXPIRED("EXPIRED");

		private final String apiReason;

		Status(String apiReason) {
			this.apiReason = apiReason;
		}
	}

	private record Session(UUID playerId, long expiresAt, String deviceId, Status status, long lastSeenAt) {
	}
}
