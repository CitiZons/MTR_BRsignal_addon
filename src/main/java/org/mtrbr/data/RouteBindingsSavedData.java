package org.mtrbr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端持久化的进路绑定数据：信号机方块位置 -> 该信号机的进路绑定列表。
 */
public final class RouteBindingsSavedData extends SavedData {

	public static final String NAME = "mtr_brsignal_addon_route_bindings";
	private static final String KEY_BINDINGS = "bindings";
	private static final String KEY_NODE_BINDINGS = "node_bindings";
	private static final String KEY_INDICATOR_BINDINGS = "indicator_bindings";
	private static final String KEY_SIGNAL_NAMES = "signal_names";
	private static final String KEY_NODE = "node";
	private static final String KEY_LIST = "list";

	private final Map<BlockPos, List<RouteBinding>> bindings = new HashMap<>();
	private final Map<BlockPos, NodeBinding> nodeBindings = new HashMap<>();
	private final Map<BlockPos, BlockPos> indicatorBindings = new HashMap<>();
	private final Map<BlockPos, String> signalNames = new HashMap<>();

	public static RouteBindingsSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(RouteBindingsSavedData::load, RouteBindingsSavedData::new, NAME);
	}

	private static RouteBindingsSavedData load(CompoundTag tag) {
		final RouteBindingsSavedData data = new RouteBindingsSavedData();
		final CompoundTag bindingsTag = tag.getCompound(KEY_BINDINGS);
		for (final String key : bindingsTag.getAllKeys()) {
			final String[] parts = key.split(",");
			if (parts.length != 3) {
				continue;
			}
			final BlockPos signalPos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			final List<RouteBinding> list = new ArrayList<>();
			for (final Tag entry : bindingsTag.getList(key, Tag.TAG_COMPOUND)) {
				list.add(RouteBinding.fromTag((CompoundTag) entry));
			}
			data.bindings.put(signalPos, list);
		}
		final CompoundTag nodeBindingsTag = tag.getCompound(KEY_NODE_BINDINGS);
		for (final String key : nodeBindingsTag.getAllKeys()) {
			final String[] parts = key.split(",");
			if (parts.length != 3) {
				continue;
			}
			final BlockPos signalPos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			data.nodeBindings.put(signalPos, NodeBinding.fromTag(nodeBindingsTag.getCompound(key)));
		}
		final CompoundTag indicatorBindingsTag = tag.getCompound(KEY_INDICATOR_BINDINGS);
		for (final String key : indicatorBindingsTag.getAllKeys()) {
			final String[] parts = key.split(",");
			if (parts.length != 3) {
				continue;
			}
			final BlockPos indicatorPos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			data.indicatorBindings.put(indicatorPos, new BlockPos(
					indicatorBindingsTag.getCompound(key).getInt("x"),
					indicatorBindingsTag.getCompound(key).getInt("y"),
					indicatorBindingsTag.getCompound(key).getInt("z")));
		}
		final CompoundTag signalNamesTag = tag.getCompound(KEY_SIGNAL_NAMES);
		for (final String key : signalNamesTag.getAllKeys()) {
			final String[] parts = key.split(",");
			if (parts.length != 3) {
				continue;
			}
			final BlockPos signalPos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			data.signalNames.put(signalPos, signalNamesTag.getString(key));
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		final CompoundTag bindingsTag = new CompoundTag();
		for (final Map.Entry<BlockPos, List<RouteBinding>> entry : bindings.entrySet()) {
			final BlockPos pos = entry.getKey();
			final ListTag list = new ListTag();
			for (final RouteBinding binding : entry.getValue()) {
				list.add(binding.toTag());
			}
			bindingsTag.put(pos.getX() + "," + pos.getY() + "," + pos.getZ(), list);
		}
		tag.put(KEY_BINDINGS, bindingsTag);
		final CompoundTag nodeBindingsTag = new CompoundTag();
		for (final Map.Entry<BlockPos, NodeBinding> entry : nodeBindings.entrySet()) {
			final BlockPos pos = entry.getKey();
			nodeBindingsTag.put(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue().toTag());
		}
		tag.put(KEY_NODE_BINDINGS, nodeBindingsTag);
		final CompoundTag indicatorBindingsTag = new CompoundTag();
		for (final Map.Entry<BlockPos, BlockPos> entry : indicatorBindings.entrySet()) {
			final BlockPos indicatorPos = entry.getKey();
			final BlockPos signalPos = entry.getValue();
			final CompoundTag posTag = new CompoundTag();
			posTag.putInt("x", signalPos.getX());
			posTag.putInt("y", signalPos.getY());
			posTag.putInt("z", signalPos.getZ());
			indicatorBindingsTag.put(indicatorPos.getX() + "," + indicatorPos.getY() + "," + indicatorPos.getZ(), posTag);
		}
		tag.put(KEY_INDICATOR_BINDINGS, indicatorBindingsTag);
		final CompoundTag signalNamesTag = new CompoundTag();
		for (final Map.Entry<BlockPos, String> entry : signalNames.entrySet()) {
			final BlockPos pos = entry.getKey();
			signalNamesTag.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
		}
		tag.put(KEY_SIGNAL_NAMES, signalNamesTag);
		return tag;
	}

	public void set(BlockPos signalPos, BlockPos nodePos, String content) {
		bindings.computeIfAbsent(signalPos.immutable(), ignored -> new ArrayList<>()).removeIf(binding -> binding.node().equals(nodePos));
		bindings.get(signalPos.immutable()).add(new RouteBinding(nodePos.immutable(), content));
		setDirty();
	}

	public void remove(BlockPos signalPos, BlockPos nodePos) {
		final List<RouteBinding> list = bindings.get(signalPos);
		if (list != null) {
			list.removeIf(binding -> binding.node().equals(nodePos));
			if (list.isEmpty()) {
				bindings.remove(signalPos);
			}
			setDirty();
		}
	}

	public Map<BlockPos, List<RouteBinding>> toClientMap() {
		final Map<BlockPos, List<RouteBinding>> copy = new LinkedHashMap<>();
		for (final Map.Entry<BlockPos, List<RouteBinding>> entry : bindings.entrySet()) {
			copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return copy;
	}

	public java.util.Set<BlockPos> getRouteBindingSignalPositions() {
		return java.util.Set.copyOf(bindings.keySet());
	}

	/** Server-side read used to project which route content an authorization opens. */
	public List<RouteBinding> getBindings(BlockPos signalPos) {
		return new ArrayList<>(bindings.getOrDefault(signalPos, List.of()));
	}

	public void setNodeBinding(BlockPos signalPos, BlockPos nodePos) {
		nodeBindings.put(signalPos.immutable(), new NodeBinding(nodePos.immutable(), false));
		setDirty();
	}

	public void toggleNodeBindingDirection(BlockPos signalPos) {
		final NodeBinding binding = nodeBindings.get(signalPos);
		if (binding != null) {
			nodeBindings.put(signalPos.immutable(), new NodeBinding(binding.node(), !binding.reversed()));
		} else {
			// 自动绑定（未手动指定节点）：只记录方向反向
			nodeBindings.put(signalPos.immutable(), new NodeBinding(null, true));
		}
		setDirty();
	}

	public Map<BlockPos, NodeBinding> getNodeBindings() {
		return new LinkedHashMap<>(nodeBindings);
	}

	/**
	 * Signal positions explicitly managed by this addon. The server must not
	 * discover arbitrary unloaded signal blocks as authoritative control faces.
	 */
	public java.util.Set<BlockPos> getManagedSignalPositions() {
		final java.util.Set<BlockPos> result = new java.util.LinkedHashSet<>();
		result.addAll(bindings.keySet());
		result.addAll(nodeBindings.keySet());
		result.addAll(signalNames.keySet());
		return java.util.Set.copyOf(result);
	}

	/** 记录进路指示器 -> 信号机的绑定（与服务端方块实体 NBT 双写，防止区块保存时序导致丢失）。 */
	public void setIndicatorBinding(BlockPos indicatorPos, BlockPos signalPos) {
		indicatorBindings.put(indicatorPos.immutable(), signalPos.immutable());
		setDirty();
	}

	public void removeIndicatorBinding(BlockPos indicatorPos) {
		if (indicatorBindings.remove(indicatorPos) != null) {
			setDirty();
		}
	}

	public Map<BlockPos, BlockPos> getIndicatorBindings() {
		return new LinkedHashMap<>(indicatorBindings);
	}

	/** 设置/清除信号机命名（空字符串表示清除）。 */
	public void setSignalName(BlockPos signalPos, String name) {
		if (name == null || name.isEmpty()) {
			if (signalNames.remove(signalPos) != null) {
				setDirty();
			}
		} else {
			signalNames.put(signalPos.immutable(), name);
			setDirty();
		}
	}

	public Map<BlockPos, String> getSignalNames() {
		return new LinkedHashMap<>(signalNames);
	}

	/** Idempotently removes every persisted binding owned by a deleted signal. */
	public boolean clearSignalBindings(BlockPos signalPos) {
		if (signalPos == null) return false;
		boolean changed = bindings.remove(signalPos) != null;
		changed |= nodeBindings.remove(signalPos) != null;
		changed |= signalNames.remove(signalPos) != null;
		changed |= indicatorBindings.entrySet().removeIf(entry -> signalPos.equals(entry.getValue()));
		if (changed) setDirty();
		return changed;
	}

	/** Idempotently removes bindings that reference a deleted node. */
	public boolean clearNodeBindings(BlockPos nodePos) {
		if (nodePos == null) return false;
		boolean changed = bindings.entrySet().removeIf(entry -> {
			final List<RouteBinding> list = entry.getValue();
			list.removeIf(binding -> nodePos.equals(binding.node()));
			return list.isEmpty();
		});
		changed |= nodeBindings.entrySet().removeIf(entry -> nodePos.equals(entry.getValue().node()));
		if (changed) setDirty();
		return changed;
	}
}
