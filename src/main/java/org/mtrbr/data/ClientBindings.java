package org.mtrbr.data;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端保存的服务端同步过来的进路绑定数据镜像。 */
public final class ClientBindings {

	private static final Map<BlockPos, List<RouteBinding>> BINDINGS = new LinkedHashMap<>();
	private static final Map<BlockPos, NodeBinding> NODE_BINDINGS = new LinkedHashMap<>();

	private ClientBindings() {
	}

	public static void setAll(Map<BlockPos, List<RouteBinding>> bindings, Map<BlockPos, NodeBinding> nodeBindings) {
		BINDINGS.clear();
		BINDINGS.putAll(bindings);
		NODE_BINDINGS.clear();
		NODE_BINDINGS.putAll(nodeBindings);
	}

	public static List<RouteBinding> get(BlockPos signalPos) {
		return BINDINGS.getOrDefault(signalPos, Collections.emptyList());
	}

	public static Map<BlockPos, List<RouteBinding>> getAll() {
		return Collections.unmodifiableMap(BINDINGS);
	}

	public static boolean isEmpty() {
		return BINDINGS.isEmpty();
	}

	public static List<RouteBinding> getAllBindings() {
		final List<RouteBinding> result = new ArrayList<>();
		BINDINGS.values().forEach(result::addAll);
		return result;
	}

	public static NodeBinding getNodeBinding(BlockPos signalPos) {
		return NODE_BINDINGS.get(signalPos);
	}

	/** 查找绑定到该节点的信号机（用于左键轮换方向）。 */
	public static BlockPos getSignalForNode(BlockPos nodePos) {
		for (final Map.Entry<BlockPos, NodeBinding> entry : NODE_BINDINGS.entrySet()) {
			if (nodePos.equals(entry.getValue().node())) {
				return entry.getKey();
			}
		}
		return null;
	}
}
