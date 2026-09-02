package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.BlockEvent;
import org.mtr.mod.block.BlockSignalBase;
import org.mtrbr.data.SignalBlockSavedData;
import org.mtrbr.data.RouteBindingsSavedData;
import org.mtrbr.network.Network;
import org.mtrbr.network.SyncRouteBindingsPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Server-only index of loaded MTR signal blocks. Client chunk visibility is never used. */
public final class ServerSignalRegistry {
	private static final Map<String, Map<Long, Set<BlockPos>>> SIGNALS_BY_CHUNK = new HashMap<>();
	private static final Map<String, Long> REVISIONS = new HashMap<>();

	private ServerSignalRegistry() {
	}

	public static void onChunkLoad(ChunkEvent.Load event) {
		if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
			return;
		}
		final Set<BlockPos> signals = new HashSet<>();
		for (final BlockPos position : chunk.getBlockEntitiesPos()) {
			if (level.getBlockState(position).getBlock() instanceof BlockSignalBase) {
				signals.add(position.immutable());
			}
		}
		final SignalBlockSavedData saved = SignalBlockSavedData.get(level);
		saved.getSignalFaceDefinitions().values().stream()
				.map(SignalBlockSavedData.SignalFaceDefinition::signalPos)
				.filter(position -> position != null && position.getX() >> 4 == chunk.getPos().x && position.getZ() >> 4 == chunk.getPos().z)
				.filter(position -> !(level.getBlockState(position).getBlock() instanceof BlockSignalBase))
				.forEach(saved::removeSignalFaceDefinitions);
		final RouteBindingsSavedData bindingsData = RouteBindingsSavedData.get(level);
		final Set<BlockPos> configuredSignals = new HashSet<>(bindingsData.getManagedSignalPositions());
		configuredSignals.addAll(bindingsData.getRouteBindingSignalPositions());
		for (final BlockPos signalPos : configuredSignals) {
			if (signalPos.getX() >> 4 == chunk.getPos().x && signalPos.getZ() >> 4 == chunk.getPos().z
					&& !(level.getBlockState(signalPos).getBlock() instanceof BlockSignalBase)) {
				bindingsData.clearSignalBindings(signalPos);
			}
		}
		for (final Map.Entry<BlockPos, org.mtrbr.data.NodeBinding> entry : bindingsData.getNodeBindings().entrySet()) {
			final BlockPos nodePos = entry.getValue().node();
			if (nodePos != null && nodePos.getX() >> 4 == chunk.getPos().x && nodePos.getZ() >> 4 == chunk.getPos().z
					&& !(level.getBlockState(nodePos).getBlock() instanceof org.mtr.mod.block.BlockNode)) {
				bindingsData.clearNodeBindings(nodePos);
			}
		}
		for (final Map.Entry<BlockPos, java.util.List<org.mtrbr.data.RouteBinding>> entry : bindingsData.toClientMap().entrySet()) {
			for (final org.mtrbr.data.RouteBinding routeBinding : entry.getValue()) {
				final BlockPos nodePos = routeBinding.node();
				if (nodePos != null && nodePos.getX() >> 4 == chunk.getPos().x && nodePos.getZ() >> 4 == chunk.getPos().z
						&& !(level.getBlockState(nodePos).getBlock() instanceof org.mtr.mod.block.BlockNode)) {
					bindingsData.clearNodeBindings(nodePos);
				}
			}
		}
		synchronized (SIGNALS_BY_CHUNK) {
			SIGNALS_BY_CHUNK.computeIfAbsent(dimension(level), ignored -> new HashMap<>()).put(chunk.getPos().toLong(), Set.copyOf(signals));
			REVISIONS.merge(dimension(level), 1L, Long::sum);
		}
	}

	public static void onChunkUnload(ChunkEvent.Unload event) {
		if (!(event.getLevel() instanceof ServerLevel level)) {
			return;
		}
		synchronized (SIGNALS_BY_CHUNK) {
			final Map<Long, Set<BlockPos>> chunks = SIGNALS_BY_CHUNK.get(dimension(level));
			if (chunks != null) {
				if (chunks.remove(event.getChunk().getPos().toLong()) != null) {
					REVISIONS.merge(dimension(level), 1L, Long::sum);
				}
			}
		}
	}

	public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (event.getLevel() instanceof ServerLevel level) {
			refreshChunk(level, event.getPos());
		}
	}

	public static void onBlockBreak(BlockEvent.BreakEvent event) {
		if (event.getLevel() instanceof ServerLevel level) {
			final BlockPos deletedPos = event.getPos().immutable();
			final boolean wasSignal = level.getBlockState(deletedPos).getBlock() instanceof org.mtr.mod.block.BlockSignalBase;
			final boolean wasNode = level.getBlockState(deletedPos).getBlock() instanceof org.mtr.mod.block.BlockNode;
			// BreakEvent fires before the block-state replacement. Refresh on the
			// next server task so the registry observes the post-break world.
			level.getServer().execute(() -> {
				if (wasSignal && !(level.getBlockState(deletedPos).getBlock() instanceof org.mtr.mod.block.BlockSignalBase)) {
					RouteBindingsSavedData.get(level).clearSignalBindings(deletedPos);
					SignalBlockSavedData.get(level).removeSignalFaceDefinitions(deletedPos);
				}
				if (wasNode && !(level.getBlockState(deletedPos).getBlock() instanceof org.mtr.mod.block.BlockNode)) {
					RouteBindingsSavedData.get(level).clearNodeBindings(deletedPos);
				}
				refreshChunk(level, deletedPos);
				ServerAspectManager.invalidateTopology(level);
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(level);
				Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.DIMENSION.with(level::dimension),
						new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			});
		}
	}

	public static Set<BlockPos> getSignals(ServerLevel level) {
		final Set<BlockPos> result = new HashSet<>();
		synchronized (SIGNALS_BY_CHUNK) {
			SIGNALS_BY_CHUNK.getOrDefault(dimension(level), Map.of()).values().forEach(result::addAll);
		}
		return Set.copyOf(result);
	}

	public static long getRevision(ServerLevel level) {
		synchronized (SIGNALS_BY_CHUNK) {
			return REVISIONS.getOrDefault(dimension(level), 0L);
		}
	}

	/** Clears the loaded-signal index when the server stops. */
	public static void resetAll() {
		synchronized (SIGNALS_BY_CHUNK) {
			SIGNALS_BY_CHUNK.clear();
			REVISIONS.clear();
		}
	}

	private static void refreshChunk(ServerLevel level, BlockPos position) {
		if (!(level.getChunk(position) instanceof LevelChunk chunk)) {
			return;
		}
		final Set<BlockPos> signals = new HashSet<>();
		for (final BlockPos candidate : chunk.getBlockEntitiesPos()) {
			if (level.getBlockState(candidate).getBlock() instanceof BlockSignalBase) {
				signals.add(candidate.immutable());
			}
		}
		final String dimension = dimension(level);
		synchronized (SIGNALS_BY_CHUNK) {
			final Map<Long, Set<BlockPos>> chunks = SIGNALS_BY_CHUNK.computeIfAbsent(dimension, ignored -> new HashMap<>());
			final Set<BlockPos> previous = chunks.put(chunk.getPos().toLong(), Set.copyOf(signals));
			REVISIONS.merge(dimension, 1L, Long::sum);
			if (previous != null) {
				final SignalBlockSavedData saved = SignalBlockSavedData.get(level);
				previous.stream().filter(signal -> !signals.contains(signal)).forEach(saved::removeSignalFaceDefinitions);
			}
		}
		ServerAspectManager.invalidateTopology(level);
	}

	private static String dimension(ServerLevel level) {
		return level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
	}
}
