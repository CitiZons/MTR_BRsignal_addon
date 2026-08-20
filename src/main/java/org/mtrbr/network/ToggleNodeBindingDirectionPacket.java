package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.data.RouteBindingsSavedData;

import java.util.function.Supplier;

/** C2S：切换信号机手动节点绑定的方向（正/反）。 */
public final class ToggleNodeBindingDirectionPacket {

	private final BlockPos signalPos;

	public ToggleNodeBindingDirectionPacket(BlockPos signalPos) {
		this.signalPos = signalPos;
	}

	public static void encode(ToggleNodeBindingDirectionPacket message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.signalPos);
	}

	public static ToggleNodeBindingDirectionPacket decode(FriendlyByteBuf buffer) {
		return new ToggleNodeBindingDirectionPacket(buffer.readBlockPos());
	}

	public static void handle(ToggleNodeBindingDirectionPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			if (context.getSender() != null && context.getSender().level() instanceof ServerLevel serverLevel) {
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
				data.toggleNodeBindingDirection(message.signalPos);
				System.out.println("[MTRBR-BIND] toggle-direction signal=" + message.signalPos
						+ " reversed=" + data.getNodeBindings().get(message.signalPos).reversed()
						+ " by=" + context.getSender().getGameProfile().getName());
				org.mtrbr.server.ServerAspectManager.invalidateTopology(serverLevel);
				Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			}
		});
		context.setPacketHandled(true);
	}
}
