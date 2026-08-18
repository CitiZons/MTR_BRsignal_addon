package org.mtrbr.data;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 客户端保存的服务端同步过来的“进路指示器 -> 信号机”绑定镜像。 */
public final class ClientIndicatorBindings {

	private static final Map<BlockPos, BlockPos> BINDINGS = new LinkedHashMap<>();

	private ClientIndicatorBindings() {
	}

	public static void setAll(Map<BlockPos, BlockPos> bindings) {
		BINDINGS.clear();
		BINDINGS.putAll(bindings);
	}

	public static void set(BlockPos indicatorPos, BlockPos signalPos) {
		BINDINGS.put(indicatorPos.immutable(), signalPos == null ? null : signalPos.immutable());
	}

	public static void remove(BlockPos indicatorPos) {
		BINDINGS.remove(indicatorPos);
	}

	public static BlockPos get(BlockPos indicatorPos) {
		return BINDINGS.get(indicatorPos);
	}

	public static Map<BlockPos, BlockPos> getAll() {
		return Collections.unmodifiableMap(BINDINGS);
	}
}
