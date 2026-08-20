package org.mtrbr.client;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** Display-only cache populated by the authoritative server. */
public final class ServerAspectCache {
	private static final Map<Key, DisplayState> ASPECTS = new HashMap<>();

	private ServerAspectCache() {
	}

	public static void replace(Map<Key, DisplayState> aspects) {
		ASPECTS.clear();
		ASPECTS.putAll(aspects);
	}

	public static Integer get(BlockPos signalPos, boolean reversed) {
		final DisplayState state = ASPECTS.get(new Key(signalPos, reversed));
		return state == null ? null : state.aspect();
	}

	public static DisplayState getState(BlockPos signalPos, boolean reversed) {
		return ASPECTS.get(new Key(signalPos, reversed));
	}

	public record Key(BlockPos signalPos, boolean reversed) {
	}

	/** Display-only data from the server; it has no client-side authority. */
	public record DisplayState(int aspect, String authorizationId, String routeContent, long revision) {
		public DisplayState {
			authorizationId = authorizationId == null ? "" : authorizationId;
			routeContent = routeContent == null ? "" : routeContent;
		}
	}
}
