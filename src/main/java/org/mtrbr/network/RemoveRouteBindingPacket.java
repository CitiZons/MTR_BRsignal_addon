package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.data.RouteBindingsSavedData;

import java.util.function.Supplier;

/** C2S：删除信号机的一条进路绑定。 */
public final class RemoveRouteBindingPacket {

	private final BlockPos signalPos;
	private final BlockPos nodePos;

	public RemoveRouteBindingPacket(BlockPos signalPos, BlockPos nodePos) {
		this.signalPos = signalPos;
		this.nodePos = nodePos;
	}

	public static void encode(RemoveRouteBindingPacket message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.signalPos);
		buffer.writeBlockPos(message.nodePos);
	}

	public static RemoveRouteBindingPacket decode(FriendlyByteBuf buffer) {
		return new RemoveRouteBindingPacket(buffer.readBlockPos(), buffer.readBlockPos());
	}

	public static void handle(RemoveRouteBindingPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			if (context.getSender() != null && context.getSender().level() instanceof ServerLevel serverLevel
					&& PacketValidation.canEdit(context.getSender(), serverLevel, message.signalPos)
					&& PacketValidation.canEdit(context.getSender(), serverLevel, message.nodePos)
					&& PacketValidation.isSignal(serverLevel, message.signalPos)
					&& PacketValidation.isNode(serverLevel, message.nodePos)) {
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
				data.remove(message.signalPos, message.nodePos);
				org.mtrbr.server.ServerAspectManager.invalidateTopology(serverLevel);
				Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			}
		});
		context.setPacketHandled(true);
	}
}
