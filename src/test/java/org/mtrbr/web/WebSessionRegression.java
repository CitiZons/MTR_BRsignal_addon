package org.mtrbr.web;

import net.minecraft.nbt.CompoundTag;
import org.mtrbr.data.WebTokenPermissionsSavedData;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Token ownership, lifecycle and persistent generation restrictions without a game server. */
public final class WebSessionRegression {
	public static void main(String[] args) throws Exception {
		try {
			permissions();
			ownership();
			activeList();
			System.out.println("Web token regression: 3 cases passed.");
		} finally {
			WebSessionManager.reset();
		}
	}

	private static void permissions() {
		WebSessionManager.reset();
		final UUID player = UUID.randomUUID(), other = UUID.randomUUID();
		final var permissions = new WebTokenPermissionsSavedData();
		check(permissions.isEnabled(player), "generation defaults to enabled");
		check(!WebSessionManager.issue(player, false, permissions.isEnabled(player)).issued(), "non-operator cannot gain dispatch credentials");
		final String existing = WebSessionManager.issue(player, true, permissions.isEnabled(player)).token();
		check(!existing.isEmpty(), "enabled operator can generate");
		permissions.setEnabled(player, false);
		final var loaded = WebTokenPermissionsSavedData.load(permissions.save(new CompoundTag()));
		check(!loaded.isEnabled(player) && loaded.isEnabled(other), "restriction survives NBT reload and stays player-specific");
		check(!WebSessionManager.issue(player, true, loaded.isEnabled(player)).issued(), "disabled generation is rejected at issue service");
		check(WebSessionManager.list(player).size() == 1, "denial creates no token");
		check(WebSessionManager.listActive().get(player).get(0).token().equals(existing), "generation ban does not revoke an existing token");
		loaded.setEnabled(player, true);
		final var restored = WebTokenPermissionsSavedData.load(loaded.save(new CompoundTag()));
		check(restored.isEnabled(player) && WebSessionManager.issue(player, true, restored.isEnabled(player)).issued(), "enable persists and restores issuance");
	}

	private static void ownership() {
		WebSessionManager.reset();
		final UUID player = UUID.randomUUID(), other = UUID.randomUUID();
		final String first = WebSessionManager.issue(player, true, true).token();
		final String second = WebSessionManager.issue(player, true, true).token();
		final String third = WebSessionManager.issue(player, true, true).token();
		check(!WebSessionManager.revoke(other, second), "cannot revoke another player's token");
		check(WebSessionManager.list(other).isEmpty(), "personal list isolates owners");
		check(WebSessionManager.revoke(player, 1), "legacy numbered command still works");
		check(WebSessionManager.revoke(player, second), "chat selection revokes original token after renumbering");
		check(!WebSessionManager.revoke(player, second), "repeated stale chat click cannot revoke another token");
		check(WebSessionManager.list(player).stream().map(WebSessionManager.TokenView::token).toList().equals(List.of(third)), "remaining token is untouched");
		check(!WebSessionManager.listActive().get(player).stream().anyMatch(view -> view.token().equals(first)), "revoked token excluded from OP list");
		for (int i = 0; i < 4; i++) check(WebSessionManager.issue(player, true, true).issued(), "issuance up to limit");
		check(!WebSessionManager.issue(player, true, true).issued(), "five-token limit enforced");
	}

	private static void activeList() throws Exception {
		WebSessionManager.reset();
		final UUID active = UUID.randomUUID(), offline = UUID.randomUUID(), expired = UUID.randomUUID(), leaked = UUID.randomUUID();
		final String activeToken = WebSessionManager.issue(active, true, true).token();
		WebSessionManager.issue(offline, true, true);
		WebSessionManager.invalidateForOfflinePlayer(offline);
		final String expiredToken = WebSessionManager.issue(expired, true, true).token();
		final String leakedToken = WebSessionManager.issue(leaked, true, true).token();
		final Field sessionsField = WebSessionManager.class.getDeclaredField("SESSIONS");
		sessionsField.setAccessible(true);
		@SuppressWarnings("unchecked") final Map<String, Object> sessions = (Map<String, Object>) sessionsField.get(null);
		final var constructor = sessions.get(expiredToken).getClass().getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		sessions.put(expiredToken, constructor.newInstance(expired, 0L, "", WebSessionManager.Status.ACTIVE, 0L));
		sessions.put(leakedToken, constructor.newInstance(leaked, Long.MAX_VALUE, "device", WebSessionManager.Status.LEAKED, 0L));
		final var all = WebSessionManager.listActive();
		check(all.size() == 1 && all.get(active).get(0).token().equals(activeToken), "OP list groups only live, non-leaked, non-expired tokens by owner");
		check(WebSessionManager.list(expired).isEmpty(), "listing expires stale tokens before displaying them");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
