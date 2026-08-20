package org.mtrbr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.server.RouteRequestManager;
import org.mtrbr.server.SectionStateManager;

import java.util.function.Supplier;

/** C2S 请求最新调度快照。 */
public final class RequestDispatcherDataPacket {
	public RequestDispatcherDataPacket() {
	}

	public static void encode(RequestDispatcherDataPacket message, FriendlyByteBuf buffer) {
	}

	public static RequestDispatcherDataPacket decode(FriendlyByteBuf buffer) {
		return new RequestDispatcherDataPacket();
	}

	public static void handle(RequestDispatcherDataPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		final ServerPlayer player = context.getSender();
		context.enqueueWork(() -> {
			if (player != null && player.level() instanceof ServerLevel level) {
				final Simulator simulator = SectionStateManager.getSimulator(level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath());
				if (simulator != null) {
					Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncDispatcherDataPacket(RouteRequestManager.getRequestSnapshots(simulator)));
				}
			}
		});
		context.setPacketHandled(true);
	}
}
