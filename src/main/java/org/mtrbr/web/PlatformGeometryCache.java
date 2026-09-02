package org.mtrbr.web;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import org.mtr.mod.block.BlockPlatform;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cache of platform blocks observed in loaded chunks.
 *
 * Web topology and diagnostics may query this cache, but must never load a
 * chunk to populate it. Entries survive chunk unload so the web map remains
 * stable while a station is inactive.
 */
public final class PlatformGeometryCache {

	private static final Map<String, Map<Long, Set<Long>>> PLATFORM_BLOCKS = new HashMap<>();
	private static final Map<String, Long> REVISIONS = new HashMap<>();

	private PlatformGeometryCache() {
	}

	public static void onChunkLoad(ChunkEvent.Load event) {
		if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
			refresh(level, chunk);
		}
	}

	public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (event.getLevel() instanceof ServerLevel level) {
			refreshLoadedChunk(level, event.getPos());
		}
	}

	public static void onBlockBreak(BlockEvent.BreakEvent event) {
		if (event.getLevel() instanceof ServerLevel level) {
			// BreakEvent runs before the replacement state is installed.
			level.getServer().execute(() -> refreshLoadedChunk(level, event.getPos()));
		}
	}

	public static boolean isPlatformAt(ServerLevel level, BlockPos position) {
		final long chunk = ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4);
		synchronized (PLATFORM_BLOCKS) {
			return PLATFORM_BLOCKS.getOrDefault(dimension(level), Map.of()).getOrDefault(chunk, Set.of()).contains(position.asLong());
		}
	}

	public static long getRevision(ServerLevel level) {
		synchronized (PLATFORM_BLOCKS) {
			return REVISIONS.getOrDefault(dimension(level), 0L);
		}
	}

	/** Returns only already-observed platform blocks inside the supplied world-space box. */
	public static Set<BlockPos> platformBlocksInBounds(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
		final Set<BlockPos> result = new HashSet<>();
		final int minChunkX = minX >> 4;
		final int maxChunkX = maxX >> 4;
		final int minChunkZ = minZ >> 4;
		final int maxChunkZ = maxZ >> 4;
		synchronized (PLATFORM_BLOCKS) {
			final Map<Long, Set<Long>> byChunk = PLATFORM_BLOCKS.getOrDefault(dimension(level), Map.of());
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
					for (final long packed : byChunk.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), Set.of())) {
						final BlockPos position = BlockPos.of(packed);
						if (position.getX() >= minX && position.getX() <= maxX && position.getZ() >= minZ && position.getZ() <= maxZ) result.add(position);
					}
				}
			}
		}
		return result;
	}

	public static void resetAll() {
		synchronized (PLATFORM_BLOCKS) {
			PLATFORM_BLOCKS.clear();
			REVISIONS.clear();
		}
	}

	private static void refreshLoadedChunk(ServerLevel level, BlockPos position) {
		// This guard is the server-thread safety boundary: getChunk is only used
		// after proving the chunk is already present, never as a load request.
		if (level.hasChunkAt(position)) {
			if (level.getChunk(position) instanceof LevelChunk chunk) {
				refresh(level, chunk);
			}
		}
	}

	private static void refresh(ServerLevel level, LevelChunk chunk) {
		final Set<Long> platforms = new HashSet<>();
		final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
		final int minX = chunk.getPos().getMinBlockX();
		final int minZ = chunk.getPos().getMinBlockZ();
		for (int x = minX; x < minX + 16; x++) {
			for (int z = minZ; z < minZ + 16; z++) {
				for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
					position.set(x, y, z);
					if (chunk.getBlockState(position).getBlock() instanceof BlockPlatform) {
						platforms.add(position.asLong());
					}
				}
			}
		}

		final String dimension = dimension(level);
		final long chunkId = chunk.getPos().toLong();
		final Set<Long> snapshot = Set.copyOf(platforms);
		synchronized (PLATFORM_BLOCKS) {
			final Map<Long, Set<Long>> byChunk = PLATFORM_BLOCKS.computeIfAbsent(dimension, ignored -> new HashMap<>());
			final Set<Long> previous = byChunk.put(chunkId, snapshot);
			if (!snapshot.equals(previous)) {
				REVISIONS.merge(dimension, 1L, Long::sum);
			}
		}
	}

	private static String dimension(ServerLevel level) {
		return level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
	}
}
