package org.mtrbr.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.mtr.core.data.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent WebUI depot-path overrides. The node sequence, rather than a
 * cached PathData list, is saved so a loaded world always resolves it against
 * the live MTR rail graph.
 */
public final class ManualDepotPathSavedData extends SavedData {
	private static final String NAME = "mtr_brsignal_addon_manual_depot_paths";
	private static final String KEY_PATHS = "paths";
	private static final String KEY_NODES = "nodes";
	private static final String KEY_SECTIONS = "sections";
	private final Map<String, List<Position>> paths = new TreeMap<>();
	private final Map<String, List<String>> sections = new TreeMap<>();

	public static ManualDepotPathSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(ManualDepotPathSavedData::load, ManualDepotPathSavedData::new, NAME);
	}

	private static ManualDepotPathSavedData load(CompoundTag tag) {
		final ManualDepotPathSavedData data = new ManualDepotPathSavedData();
		final CompoundTag pathsTag = tag.getCompound(KEY_PATHS);
		for (final String depotId : pathsTag.getAllKeys()) {
			final List<Position> nodes = new ArrayList<>();
			final CompoundTag value = pathsTag.getCompound(depotId);
			final ListTag nodesTag = value.contains(KEY_NODES, Tag.TAG_LIST) ? value.getList(KEY_NODES, Tag.TAG_COMPOUND) : pathsTag.getList(depotId, Tag.TAG_COMPOUND);
			for (final Tag nodeTag : nodesTag) {
				final CompoundTag node = (CompoundTag) nodeTag;
				nodes.add(new Position(node.getLong("x"), node.getLong("y"), node.getLong("z")));
			}
			if (nodes.size() >= 2) data.paths.put(depotId, List.copyOf(nodes));
			final List<String> sectionIds = new ArrayList<>();
			for (final Tag sectionTag : value.getList(KEY_SECTIONS, Tag.TAG_STRING)) sectionIds.add(sectionTag.getAsString());
			if (!sectionIds.isEmpty()) data.sections.put(depotId, List.copyOf(sectionIds));
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		final CompoundTag pathsTag = new CompoundTag();
		for (final Map.Entry<String, List<Position>> entry : paths.entrySet()) {
			final ListTag nodesTag = new ListTag();
			for (final Position position : entry.getValue()) {
				final CompoundTag node = new CompoundTag();
				node.putLong("x", position.getX());
				node.putLong("y", position.getY());
				node.putLong("z", position.getZ());
				nodesTag.add(node);
			}
			final CompoundTag wrapper = new CompoundTag();
			wrapper.put(KEY_NODES, nodesTag);
			final List<String> sectionIds = sections.get(entry.getKey());
			if (sectionIds != null) {
				final ListTag sectionTag = new ListTag();
				for (final String sectionId : sectionIds) sectionTag.add(net.minecraft.nbt.StringTag.valueOf(sectionId));
				wrapper.put(KEY_SECTIONS, sectionTag);
			}
			pathsTag.put(entry.getKey(), wrapper);
		}
		tag.put(KEY_PATHS, pathsTag);
		return tag;
	}

	public List<Position> getNodes(long depotId) {
		return paths.getOrDefault(Long.toUnsignedString(depotId, 16), List.of());
	}

	public void setNodes(long depotId, List<Position> nodes) {
		paths.put(Long.toUnsignedString(depotId, 16), List.copyOf(nodes));
		setDirty();
	}

	public void setSections(long depotId, List<String> sectionIds) {
		sections.put(Long.toUnsignedString(depotId, 16), List.copyOf(sectionIds));
		setDirty();
	}

	public List<String> getSections(long depotId) {
		return sections.getOrDefault(Long.toUnsignedString(depotId, 16), List.of());
	}
}
