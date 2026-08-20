package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.data.RouteBindingsSavedData;
import org.mtrbr.data.RouteContent;

import java.util.function.Supplier;

/** C2S：为信号机设置一条进路绑定。 */
public final class SetRouteBindingPacket {

	private final BlockPos signalPos;
	private final BlockPos nodePos;
	private final String content;

	public SetRouteBindingPacket(BlockPos signalPos, BlockPos nodePos, String content) {
		this.signalPos = signalPos;
		this.nodePos = nodePos;
		this.content = content;
	}

	public static void encode(SetRouteBindingPacket message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.signalPos);
		buffer.writeBlockPos(message.nodePos);
		buffer.writeUtf(message.content, 64);
	}

	public static SetRouteBindingPacket decode(FriendlyByteBuf buffer) {
		return new SetRouteBindingPacket(buffer.readBlockPos(), buffer.readBlockPos(), buffer.readUtf(64));
	}

	public static void handle(SetRouteBindingPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			final String validated = RouteContent.validate(message.content);
			if (validated != null && context.getSender() != null && context.getSender().level() instanceof ServerLevel serverLevel) {
				RouteBindingsSavedData.get(serverLevel).set(message.signalPos, message.nodePos, validated);
				org.mtrbr.server.ServerAspectManager.invalidateTopology(serverLevel);
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
				Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			}
		});
		context.setPacketHandled(true);
	}
}
