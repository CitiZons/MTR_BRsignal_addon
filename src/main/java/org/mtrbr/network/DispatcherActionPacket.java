package org.mtrbr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.server.RouteRequestManager;
import org.mtrbr.server.SectionStateManager;

import java.util.function.Supplier;

/** C2S 调度操作：批准或撤销指定车辆请求。 */
public final class DispatcherActionPacket {
	private final String action;
	private final long vehicleId;

	public DispatcherActionPacket(String action, long vehicleId) {
		this.action = action;
		this.vehicleId = vehicleId;
	}

	public static void encode(DispatcherActionPacket message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.action, 16);
		buffer.writeLong(message.vehicleId);
	}

	public static DispatcherActionPacket decode(FriendlyByteBuf buffer) {
		return new DispatcherActionPacket(buffer.readUtf(16), buffer.readLong());
	}

	public static void handle(DispatcherActionPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		final ServerPlayer player = context.getSender();
		context.enqueueWork(() -> {
			if (player != null && player.level() instanceof ServerLevel level) {
				final Simulator simulator = SectionStateManager.getSimulator(level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath());
				if (simulator != null) {
					if ("approve".equals(message.action)) {
						RouteRequestManager.approveWaiting(simulator, message.vehicleId);
						System.out.println("[MTRBR-DISPATCH] approve vehicle=" + message.vehicleId + " by=" + player.getGameProfile().getName());
					} else if ("revoke".equals(message.action)) {
						RouteRequestManager.revokePendingAuthorization(simulator, message.vehicleId);
						System.out.println("[MTRBR-DISPATCH] revoke vehicle=" + message.vehicleId + " by=" + player.getGameProfile().getName());
					}
				} else {
					System.out.println("[MTRBR-DISPATCH] simulator null for action=" + message.action + " vehicle=" + message.vehicleId);
				}
			}
		});
		context.setPacketHandled(true);
	}
}
