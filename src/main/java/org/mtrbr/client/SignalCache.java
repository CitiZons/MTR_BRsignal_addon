package org.mtrbr.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.mtrbr.logic.SignalLogic;
import org.mtrbr.client.ServerAspectCache;

import java.util.ArrayList;
import java.util.List;

/**
 * 附近信号机缓存：每 20 tick 扫描一次已加载区块中的信号机方块实体，
 * 缓存每个信号机及其自动作用节点，供世界连线与 HUD 使用。
 */
public final class SignalCache {

	private static final int SCAN_RADIUS = 160;
	private static final List<Entry> ENTRIES = new ArrayList<>();
	private static long lastRefresh = -1;
	private static BlockPos lastCenter;

	private SignalCache() {
	}

	public static List<Entry> getEntries(Level level, BlockPos center) {
		tick(level, center);
		return ENTRIES;
	}

	/** 以当前玩家位置为中心刷新缓存（供闭塞链解析使用）。 */
	public static void tick(Level level) {
		if (!(level instanceof ClientLevel)) {
			return;
		}
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			lastCenter = null;
			return;
		}
		tick(level, minecraft.player.blockPosition());
	}

	public static void tick(Level level, BlockPos center) {
		if (!(level instanceof ClientLevel clientLevel)) {
			return;
		}
		lastCenter = center;
		final long gameTime = level.getGameTime();
		if (gameTime != lastRefresh) {
			lastRefresh = gameTime;
			refresh(clientLevel, center);
		}
	}

	private static void refresh(ClientLevel level, BlockPos center) {
		ENTRIES.clear();
		final int chunkRadius = SCAN_RADIUS / 16 + 1;
		final int chunkX = center.getX() >> 4;
		final int chunkZ = center.getZ() >> 4;
		for (int x = -chunkRadius; x <= chunkRadius; x++) {
			for (int z = -chunkRadius; z <= chunkRadius; z++) {
				final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX + x, chunkZ + z);
				if (chunk == null) {
					continue;
				}
				for (final BlockPos pos : chunk.getBlockEntitiesPos()) {
					if (pos.distSqr(center) > (double) SCAN_RADIUS * SCAN_RADIUS) {
						continue;
					}
					if (SignalLogic.isSignalBlock(level.getBlockState(pos))) {
						final ServerAspectCache.DisplayState display = ServerAspectCache.getState(pos, false);
						if (display != null && display.nodePos() != null) {
							ENTRIES.add(new Entry(pos.immutable(), display.nodePos()));
						}
					}
				}
			}
		}
	}

	public record Entry(BlockPos signalPos, BlockPos nodePos) {
	}
}
