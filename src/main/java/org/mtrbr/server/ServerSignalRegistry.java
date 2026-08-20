package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.BlockEvent;
import org.mtr.mod.block.BlockSignalBase;

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
			// BreakEvent fires before the block-state replacement. Refresh on the
			// next server task so the registry observes the post-break world.
			level.getServer().execute(() -> refreshChunk(level, event.getPos()));
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
		synchronized (SIGNALS_BY_CHUNK) {
			SIGNALS_BY_CHUNK.computeIfAbsent(dimension(level), ignored -> new HashMap<>()).put(chunk.getPos().toLong(), Set.copyOf(signals));
			REVISIONS.merge(dimension(level), 1L, Long::sum);
		}
		ServerAspectManager.invalidateTopology(level);
	}

	private static String dimension(ServerLevel level) {
		return level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
	}
}
