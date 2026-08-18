package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.ClientIndicatorBindings;
import org.mtrbr.data.ClientSignalNames;
import org.mtrbr.data.NodeBinding;
import org.mtrbr.data.RouteBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** S2C：把服务端完整进路绑定数据同步给客户端。 */
public final class SyncRouteBindingsPacket {

	private final Map<BlockPos, List<RouteBinding>> bindings;
	private final Map<BlockPos, NodeBinding> nodeBindings;
	private final Map<BlockPos, BlockPos> indicatorBindings;
	private final Map<BlockPos, String> signalNames;

	public SyncRouteBindingsPacket(Map<BlockPos, List<RouteBinding>> bindings, Map<BlockPos, NodeBinding> nodeBindings, Map<BlockPos, BlockPos> indicatorBindings, Map<BlockPos, String> signalNames) {
		this.bindings = bindings;
		this.nodeBindings = nodeBindings;
		this.indicatorBindings = indicatorBindings;
		this.signalNames = signalNames;
	}

	public static void encode(SyncRouteBindingsPacket message, FriendlyByteBuf buffer) {
		buffer.writeInt(message.bindings.size());
		for (final Map.Entry<BlockPos, List<RouteBinding>> entry : message.bindings.entrySet()) {
			buffer.writeBlockPos(entry.getKey());
			buffer.writeInt(entry.getValue().size());
			for (final RouteBinding binding : entry.getValue()) {
				buffer.writeBlockPos(binding.node());
				buffer.writeUtf(binding.content(), 64);
			}
		}
		buffer.writeInt(message.nodeBindings.size());
		for (final Map.Entry<BlockPos, NodeBinding> entry : message.nodeBindings.entrySet()) {
			buffer.writeBlockPos(entry.getKey());
			buffer.writeBoolean(entry.getValue().node() != null);
			if (entry.getValue().node() != null) {
				buffer.writeBlockPos(entry.getValue().node());
			}
			buffer.writeBoolean(entry.getValue().reversed());
		}
		buffer.writeInt(message.indicatorBindings.size());
		for (final Map.Entry<BlockPos, BlockPos> entry : message.indicatorBindings.entrySet()) {
			buffer.writeBlockPos(entry.getKey());
			buffer.writeBoolean(entry.getValue() != null);
			if (entry.getValue() != null) {
				buffer.writeBlockPos(entry.getValue());
			}
		}
		buffer.writeInt(message.signalNames.size());
		for (final Map.Entry<BlockPos, String> entry : message.signalNames.entrySet()) {
			buffer.writeBlockPos(entry.getKey());
			buffer.writeUtf(entry.getValue(), 64);
		}
	}

	public static SyncRouteBindingsPacket decode(FriendlyByteBuf buffer) {
		final Map<BlockPos, List<RouteBinding>> bindings = new LinkedHashMap<>();
		final int signalCount = buffer.readInt();
		for (int i = 0; i < signalCount; i++) {
			final BlockPos signalPos = buffer.readBlockPos();
			final int bindingCount = buffer.readInt();
			final List<RouteBinding> list = new ArrayList<>();
			for (int j = 0; j < bindingCount; j++) {
				list.add(new RouteBinding(buffer.readBlockPos(), buffer.readUtf(64)));
			}
			bindings.put(signalPos, list);
		}
		final Map<BlockPos, NodeBinding> nodeBindings = new LinkedHashMap<>();
		final int nodeBindingCount = buffer.readInt();
		for (int i = 0; i < nodeBindingCount; i++) {
			final BlockPos signalPos = buffer.readBlockPos();
			final BlockPos nodePos = buffer.readBoolean() ? buffer.readBlockPos() : null;
			nodeBindings.put(signalPos, new NodeBinding(nodePos, buffer.readBoolean()));
		}
		final Map<BlockPos, BlockPos> indicatorBindings = new LinkedHashMap<>();
		final int indicatorBindingCount = buffer.readInt();
		for (int i = 0; i < indicatorBindingCount; i++) {
			final BlockPos indicatorPos = buffer.readBlockPos();
			final BlockPos signalPos = buffer.readBoolean() ? buffer.readBlockPos() : null;
			indicatorBindings.put(indicatorPos, signalPos);
		}
		final Map<BlockPos, String> signalNames = new LinkedHashMap<>();
		final int signalNameCount = buffer.readInt();
		for (int i = 0; i < signalNameCount; i++) {
			signalNames.put(buffer.readBlockPos(), buffer.readUtf(64));
		}
		return new SyncRouteBindingsPacket(bindings, nodeBindings, indicatorBindings, signalNames);
	}

	public static void handle(SyncRouteBindingsPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ClientBindings.setAll(message.bindings, message.nodeBindings);
			ClientIndicatorBindings.setAll(message.indicatorBindings);
			ClientSignalNames.setAll(message.signalNames);
		});
		context.setPacketHandled(true);
	}
}
