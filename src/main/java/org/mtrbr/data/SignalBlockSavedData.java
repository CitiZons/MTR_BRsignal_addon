package org.mtrbr.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.mtrbr.server.PathSnapshot;
import org.mtrbr.server.RouteRequestManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Persistent SignalFace -> directed protection boundary -> protected Rail mapping. */
public final class SignalBlockSavedData extends SavedData {
	public static final String NAME = "mtr_brsignal_addon_signal_blocks";
	private static final String KEY_VERSION = "version";
	private static final String KEY_FACES = "faces";
	private static final String KEY_OCCURRENCES = "occurrences";
	private static final String KEY_BLOCKS = "blockRails";
	private static final int CURRENT_VERSION = 8;
	private final Map<String, String> faceToBlock = new HashMap<>();
	private final Map<String, String> occurrenceToBlock = new HashMap<>();
	private final Map<String, List<String>> blockRails = new HashMap<>();
	/** Legacy values are retained only in memory until the explicit migrate command runs. */
	private final Map<String, List<String>> legacyFaceRails = new HashMap<>();
	private static volatile Map<String, Snapshot> SNAPSHOTS = Map.of();

	public static SignalBlockSavedData get(ServerLevel level) {
		final SignalBlockSavedData data = level.getDataStorage().computeIfAbsent(SignalBlockSavedData::load, SignalBlockSavedData::new, NAME);
		data.publishSnapshot(dimension(level));
		return data;
	}

	private static String dimension(ServerLevel level) { return level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath(); }

	public static Snapshot getSnapshot(String dimension) { return SNAPSHOTS.getOrDefault(dimension, new Snapshot(Map.of(), Map.of(), Map.of())); }

	private void publishSnapshot(String dimension) {
		final Map<String, Snapshot> next = new HashMap<>(SNAPSHOTS);
		next.put(dimension, new Snapshot(faceToBlock, occurrenceToBlock, blockRails));
		SNAPSHOTS = Collections.unmodifiableMap(next);
	}

	private static SignalBlockSavedData load(CompoundTag tag) {
		final SignalBlockSavedData data = new SignalBlockSavedData();
		final CompoundTag faces = tag.getCompound(KEY_FACES);
		for (final String faceId : faces.getAllKeys()) data.faceToBlock.put(faceId, faces.getString(faceId));
		final CompoundTag occurrences = tag.getCompound(KEY_OCCURRENCES);
		for (final String occurrenceKey : occurrences.getAllKeys()) data.occurrenceToBlock.put(occurrenceKey, occurrences.getString(occurrenceKey));
		final CompoundTag blocks = tag.getCompound(KEY_BLOCKS);
		for (final String blockId : blocks.getAllKeys()) data.blockRails.put(blockId, readStrings(blocks.getList(blockId, Tag.TAG_STRING)));
		// Legacy values are captured for the one-shot explicit migration only. They
		// are never returned by getBlockId/getRailIds and are not written by save().
		for (final Map.Entry<String, String> entry : data.faceToBlock.entrySet()) {
			if (entry.getValue().startsWith("legacy:")) {
				data.legacyFaceRails.put(entry.getKey(), data.blockRails.getOrDefault(entry.getValue(), List.of()));
			}
		}
		data.faceToBlock.entrySet().removeIf(entry -> !isCanonicalBlockId(entry.getValue()));
		data.occurrenceToBlock.entrySet().removeIf(entry -> !isCanonicalBlockId(entry.getValue()));
		data.blockRails.entrySet().removeIf(entry -> !isCanonicalBlockId(entry.getKey()));
		final CompoundTag legacy = tag.getCompound("blocks");
		if (!legacy.isEmpty()) {
			// Old face->rails data cannot identify B without topology. Do not guess.
			data.setDirty();
		}
		return data;
	}

	private static boolean isCanonicalBlockId(String blockId) {
		if (blockId == null || blockId.isBlank() || blockId.startsWith("legacy:") || blockId.startsWith("generated:")) return false;
		final int separator = blockId.indexOf("->");
		if (separator <= 0 || separator + 2 >= blockId.length() || blockId.indexOf("->", separator + 2) >= 0) return false;
		final String boundaryId = blockId.substring(separator + 2);
		return boundaryId.startsWith("terminal:") || !boundaryId.isBlank();
	}

	private static List<String> readStrings(ListTag tag) {
		final List<String> values = new ArrayList<>();
		for (int i = 0; i < tag.size(); i++) {
			final String value = tag.getString(i);
			if (!value.isBlank() && !values.contains(value)) values.add(value);
		}
		return List.copyOf(values);
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		tag.putInt(KEY_VERSION, CURRENT_VERSION);
		final CompoundTag faces = new CompoundTag();
		faceToBlock.forEach(faces::putString);
		tag.put(KEY_FACES, faces);
		final CompoundTag occurrences = new CompoundTag();
		occurrenceToBlock.forEach(occurrences::putString);
		tag.put(KEY_OCCURRENCES, occurrences);
		final CompoundTag blocks = new CompoundTag();
		for (final Map.Entry<String, List<String>> entry : blockRails.entrySet()) {
			final ListTag rails = new ListTag();
			entry.getValue().forEach(value -> rails.add(StringTag.valueOf(value)));
			blocks.put(entry.getKey(), rails);
		}
		tag.put(KEY_BLOCKS, blocks);
		return tag;
	}

	public String getBlockId(String faceId) { return faceToBlock.getOrDefault(faceId, ""); }
	public String getOccurrenceBlockId(String pathFingerprint, PathSnapshot.FaceTraversalKey key) {
		return occurrenceToBlock.getOrDefault(occurrenceKey(pathFingerprint, key), "");
	}
	public List<String> getRailIdsForBlock(String blockId) { return blockRails.getOrDefault(blockId, List.of()); }
	public List<String> getRailIds(String faceId) {
		final String blockId = getBlockId(faceId);
		return blockId.isEmpty() ? List.of() : getRailIdsForBlock(blockId);
	}

	public void setBlock(String faceId, String blockId, List<String> railIds) {
		final List<String> validated = railIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
		if (faceId == null || faceId.isBlank() || blockId == null || blockId.isBlank() || validated.isEmpty()) {
			faceToBlock.remove(faceId);
			if (blockId != null) blockRails.remove(blockId);
		} else {
			faceToBlock.put(faceId, blockId);
			blockRails.put(blockId, validated);
		}
		setDirty();
	}

	/** Persists an occurrence-specific projection without changing the canonical Block ID. */
	public void setOccurrenceBlock(String pathFingerprint, PathSnapshot.FaceTraversalKey key, String blockId) {
		final String occurrenceKey = occurrenceKey(pathFingerprint, key);
		if (pathFingerprint == null || pathFingerprint.isBlank() || key == null || !isCanonicalBlockId(blockId)) {
			occurrenceToBlock.remove(occurrenceKey);
		} else {
			occurrenceToBlock.put(occurrenceKey, blockId);
		}
		setDirty();
	}

	public static String occurrenceKey(String pathFingerprint, PathSnapshot.FaceTraversalKey key) {
		return pathFingerprint + "|" + key;
	}

	public record Snapshot(Map<String, String> faceToBlock, Map<String, String> occurrenceToBlock, Map<String, List<String>> blockRails) {
		public Snapshot {
			faceToBlock = Map.copyOf(faceToBlock);
			occurrenceToBlock = Map.copyOf(occurrenceToBlock);
			blockRails = Map.copyOf(blockRails);
		}
		public String getBlockId(String faceId) { return faceToBlock.getOrDefault(faceId, ""); }
		public String getOccurrenceBlockId(String pathFingerprint, PathSnapshot.FaceTraversalKey key) {
			return occurrenceToBlock.getOrDefault(occurrenceKey(pathFingerprint, key), "");
		}
		public List<String> getRailIds(String blockId) { return blockRails.getOrDefault(blockId, List.of()); }
		public String getBoundaryId(String blockId) {
			final int separator = blockId == null ? -1 : blockId.indexOf("->");
			return separator < 0 ? "" : blockId.substring(separator + 2);
		}
	}

	/** Replace persisted canonical mappings with the current canonical topology. */
	public int replaceGeneratedBlocks(Map<String, RouteRequestManager.GeneratedProtection> generated) {
		faceToBlock.clear();
		blockRails.clear();
		for (final Map.Entry<String, RouteRequestManager.GeneratedProtection> entry : generated.entrySet()) {
			final RouteRequestManager.GeneratedProtection protection = entry.getValue();
			if (!isCanonicalBlockId(protection.blockId()) || protection.railIds().isEmpty()) continue;
			faceToBlock.put(entry.getKey(), protection.blockId());
			blockRails.put(protection.blockId(), protection.railIds());
		}
		setDirty();
		return faceToBlock.size();
	}

	/**
	 * One-shot migration of legacy face/rail records using the canonical topology
	 * calculated by an explicit protection regenerate command. No runtime lookup
	 * ever consults legacyFaceRails.
	 */
	public int migrateLegacyBlocks(Map<String, RouteRequestManager.GeneratedProtection> generated) {
		final int legacyCount = legacyFaceRails.size();
		faceToBlock.clear();
		blockRails.clear();
		int migrated = 0;
		for (final Map.Entry<String, RouteRequestManager.GeneratedProtection> entry : generated.entrySet()) {
			final RouteRequestManager.GeneratedProtection protection = entry.getValue();
			if (!isCanonicalBlockId(protection.blockId()) || protection.railIds().isEmpty()) continue;
			faceToBlock.put(entry.getKey(), protection.blockId());
			blockRails.put(protection.blockId(), protection.railIds());
			if (legacyFaceRails.containsKey(entry.getKey())) migrated++;
		}
		legacyFaceRails.clear();
		setDirty();
		return migrated;
	}

	public int legacyFaceCount() { return legacyFaceRails.size(); }

	/** Adds only absent mappings during first-world initialization. */
	public int addGeneratedBlocks(Map<String, RouteRequestManager.GeneratedProtection> generated) {
		int added = 0;
		for (final Map.Entry<String, RouteRequestManager.GeneratedProtection> entry : generated.entrySet()) {
			final RouteRequestManager.GeneratedProtection protection = entry.getValue();
			if (faceToBlock.containsKey(entry.getKey()) || !isCanonicalBlockId(protection.blockId()) || protection.railIds().isEmpty()) continue;
			faceToBlock.put(entry.getKey(), protection.blockId());
			blockRails.put(protection.blockId(), protection.railIds());
			added++;
		}
		if (added > 0) setDirty();
		return added;
	}

	/** Adds only absent occurrence projections derived from observed immutable paths. */
	public int addGeneratedOccurrenceBlocks(Map<String, RouteRequestManager.GeneratedProtection> generated) {
		int added = 0;
		for (final Map.Entry<String, RouteRequestManager.GeneratedProtection> entry : generated.entrySet()) {
			final RouteRequestManager.GeneratedProtection protection = entry.getValue();
			if (occurrenceToBlock.containsKey(entry.getKey()) || !isCanonicalBlockId(protection.blockId()) || protection.railIds().isEmpty()) continue;
			occurrenceToBlock.put(entry.getKey(), protection.blockId());
			blockRails.putIfAbsent(protection.blockId(), protection.railIds());
			added++;
		}
		if (added > 0) setDirty();
		return added;
	}

	/** Deprecated face-only write. A block cannot be persisted without its B face. */
	public void setRailIds(String faceId, List<String> railIds) { setBlock(faceId, "", List.of()); }
}
