package org.mtrbr.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Generation restrictions are shared by all dimensions and survive server restarts. */
public final class WebTokenPermissionsSavedData extends SavedData {
	private static final String NAME = "mtr_brsignal_addon_web_token_permissions";
	private final Set<UUID> disabledPlayers = new HashSet<>();

	public static WebTokenPermissionsSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(WebTokenPermissionsSavedData::load,
				WebTokenPermissionsSavedData::new, NAME);
	}

	public boolean isEnabled(UUID playerId) {
		return !disabledPlayers.contains(playerId);
	}

	public void setEnabled(UUID playerId, boolean enabled) {
		if (enabled ? disabledPlayers.remove(playerId) : disabledPlayers.add(playerId)) setDirty();
	}

	public static WebTokenPermissionsSavedData load(CompoundTag tag) {
		final var data = new WebTokenPermissionsSavedData();
		for (final Tag entry : tag.getList("disabledPlayers", Tag.TAG_COMPOUND)) {
			final CompoundTag player = (CompoundTag) entry;
			if (player.hasUUID("id")) data.disabledPlayers.add(player.getUUID("id"));
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		final ListTag players = new ListTag();
		for (final UUID id : disabledPlayers) {
			final CompoundTag player = new CompoundTag();
			player.putUUID("id", id);
			players.add(player);
		}
		tag.put("disabledPlayers", players);
		return tag;
	}
}
