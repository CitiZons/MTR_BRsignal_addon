package org.mtrbr.data;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 客户端保存的服务端同步过来的信号机命名镜像。 */
public final class ClientSignalNames {

	private static final Map<BlockPos, String> NAMES = new LinkedHashMap<>();

	private ClientSignalNames() {
	}

	public static void setAll(Map<BlockPos, String> names) {
		NAMES.clear();
		NAMES.putAll(names);
	}

	/** 返回信号机命名；未命名时返回 null。 */
	public static String get(BlockPos signalPos) {
		return NAMES.get(signalPos);
	}

	public static Map<BlockPos, String> getAll() {
		return Collections.unmodifiableMap(NAMES);
	}
}
