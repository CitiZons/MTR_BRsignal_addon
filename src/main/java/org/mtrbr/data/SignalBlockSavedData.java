package org.mtrbr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtrbr.server.SignalFace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 持久化 SignalFace -> 保护 Rail 集合。服务端拓扑从存档加载，不再从车辆 Path 动态推导。 */
public final class SignalBlockSavedData extends SavedData {
	public static final String NAME = "mtr_brsignal_addon_signal_blocks";
	private static final String KEY_BLOCKS = "blocks";
	private static final String KEY_RAILS = "rails";

	private final Map<String, List<String>> blocks = new HashMap<>();

	public static SignalBlockSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(SignalBlockSavedData::load, SignalBlockSavedData::new, NAME);
	}

	private static SignalBlockSavedData load(CompoundTag tag) {
		final SignalBlockSavedData data = new SignalBlockSavedData();
		final CompoundTag blocksTag = tag.getCompound(KEY_BLOCKS);
		for (final String faceId : blocksTag.getAllKeys()) {
			final ListTag railsTag = blocksTag.getList(faceId, Tag.TAG_STRING);
			final List<String> rails = new ArrayList<>();
			for (int i = 0; i < railsTag.size(); i++) {
				rails.add(railsTag.getString(i));
			}
			data.blocks.put(faceId, rails);
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		final CompoundTag blocksTag = new CompoundTag();
		for (final Map.Entry<String, List<String>> entry : blocks.entrySet()) {
			final ListTag railsTag = new ListTag();
			for (final String railId : entry.getValue()) {
				railsTag.add(StringTag.valueOf(railId));
			}
			blocksTag.put(entry.getKey(), railsTag);
		}
		tag.put(KEY_BLOCKS, blocksTag);
		return tag;
	}

	public List<String> getRailIds(String faceId) {
		return blocks.getOrDefault(faceId, List.of());
	}

	/** 根据当前 SignalFace 拓扑和 Simulator 轨道图重建并持久化闭塞块。 */
	public void rebuild(Simulator simulator, Map<String, SignalFace> faces) {
		final Map<BlockPos, SignalFace> faceByNode = new LinkedHashMap<>();
		for (final SignalFace face : faces.values()) {
			faceByNode.putIfAbsent(face.nodePos(), face);
		}
		final Object2ObjectOpenHashMap<Position, Object2ObjectOpenHashMap<Position, Rail>> graph = simulator.positionsToRail;
		final Map<String, List<String>> next = new LinkedHashMap<>();
		for (final SignalFace face : faces.values()) {
			final Position start = position(face.nodePos());
			final Set<Position> visited = new HashSet<>();
			final List<String> railIds = new ArrayList<>();
			Position current = start;
			double direction = face.travelAngle();
			while (true) {
				if (!visited.add(current)) {
					break;
				}
				if (!current.equals(start) && faceByNode.containsKey(toBlockPos(current))) {
					break;
				}
				final Map<Position, Rail> outgoing = graph.getOrDefault(current, new Object2ObjectOpenHashMap<>());
				Position nextPosition = null;
				Rail nextRail = null;
				double bestDifference = Double.MAX_VALUE;
				for (final Map.Entry<Position, Rail> candidate : outgoing.entrySet()) {
					final double difference = circularDifference(angle(current, candidate.getKey()), direction);
					if (difference < bestDifference) {
						bestDifference = difference;
						nextPosition = candidate.getKey();
						nextRail = candidate.getValue();
					}
				}
				if (nextRail == null || nextPosition == null) {
					break;
				}
				railIds.add(nextRail.getHexId());
				direction = angle(current, nextPosition);
				current = nextPosition;
			}
			next.put(face.id(), List.copyOf(railIds));
		}
		blocks.clear();
		blocks.putAll(next);
		setDirty();
	}

	private static Position position(BlockPos pos) {
		return new Position(pos.getX(), pos.getY(), pos.getZ());
	}

	private static BlockPos toBlockPos(Position position) {
		return new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
	}

	private static double angle(Position from, Position to) {
		return Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
	}

	private static double circularDifference(double first, double second) {
		double difference = (first - second) % 360;
		if (difference < -180) {
			difference += 360;
		}
		if (difference > 180) {
			difference -= 360;
		}
		return Math.abs(difference);
	}
}
