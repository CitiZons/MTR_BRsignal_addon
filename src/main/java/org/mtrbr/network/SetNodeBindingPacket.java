package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.data.RouteBindingsSavedData;

import java.util.function.Supplier;

/** C2S：为信号机设置手动节点绑定（信号机 -> 轨道节点）。 */
public final class SetNodeBindingPacket {

	private final BlockPos signalPos;
	private final BlockPos nodePos;

	public SetNodeBindingPacket(BlockPos signalPos, BlockPos nodePos) {
		this.signalPos = signalPos;
		this.nodePos = nodePos;
	}

	public static void encode(SetNodeBindingPacket message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.signalPos);
		buffer.writeBlockPos(message.nodePos);
	}

	public static SetNodeBindingPacket decode(FriendlyByteBuf buffer) {
		return new SetNodeBindingPacket(buffer.readBlockPos(), buffer.readBlockPos());
	}

	public static void handle(SetNodeBindingPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			if (context.getSender() != null && context.getSender().level() instanceof ServerLevel serverLevel) {
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
				data.setNodeBinding(message.signalPos, message.nodePos);
				System.out.println("[MTRBR-BIND] signal=" + message.signalPos + " node=" + message.nodePos
						+ " savedNodeBindings=" + data.getNodeBindings().size()
						+ " by=" + context.getSender().getGameProfile().getName());
				org.mtrbr.server.ServerAspectManager.invalidateTopology(serverLevel);
				Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			}
		});
		context.setPacketHandled(true);
	}
}
