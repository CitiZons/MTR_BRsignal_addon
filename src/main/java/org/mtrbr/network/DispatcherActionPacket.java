package org.mtrbr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.server.RouteRequestManager;
import org.mtrbr.server.SectionStateManager;
import org.mtrbr.server.MtrbrDebugLog;

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
				if (!player.hasPermissions(2)) {
					reply(player, message, new RouteRequestManager.DispatchResult(false, "permission", ""));
					MtrbrDebugLog.event("DISPATCH", "denied action=" + message.action + " vehicle=" + message.vehicleId + " actor=" + player.getGameProfile().getName());
					System.out.println("[MTRBR-DISPATCH] denied action=" + message.action + " vehicle=" + message.vehicleId + " by=" + player.getGameProfile().getName());
					return;
				}
				final Simulator simulator = SectionStateManager.getSimulator(level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath());
				if (simulator != null) {
					if ("approve".equals(message.action)) {
						RouteRequestManager.approveWaiting(simulator, message.vehicleId, result -> reply(player, message, result));
						MtrbrDebugLog.event("DISPATCH", "approve vehicle=" + message.vehicleId + " actor=" + player.getGameProfile().getName());
						System.out.println("[MTRBR-DISPATCH] approve vehicle=" + message.vehicleId + " by=" + player.getGameProfile().getName());
					} else if ("revoke".equals(message.action)) {
						RouteRequestManager.revokePendingAuthorization(simulator, message.vehicleId, result -> reply(player, message, result));
						MtrbrDebugLog.event("DISPATCH", "revoke vehicle=" + message.vehicleId + " actor=" + player.getGameProfile().getName());
						System.out.println("[MTRBR-DISPATCH] revoke vehicle=" + message.vehicleId + " by=" + player.getGameProfile().getName());
					} else if ("override".equals(message.action)) {
						RouteRequestManager.grantOneShotOverride(simulator, message.vehicleId, result -> reply(player, message, result));
						MtrbrDebugLog.event("DISPATCH", "override vehicle=" + message.vehicleId + " actor=" + player.getGameProfile().getName());
						System.out.println("[MTRBR-DISPATCH] override vehicle=" + message.vehicleId + " by=" + player.getGameProfile().getName());
					} else {
						reply(player, message, new RouteRequestManager.DispatchResult(false, "unknown_action", ""));
						MtrbrDebugLog.event("DISPATCH", "rejected unknown action=" + message.action + " vehicle=" + message.vehicleId + " actor=" + player.getGameProfile().getName());
					}
				} else {
					reply(player, message, new RouteRequestManager.DispatchResult(false, "simulator_missing", ""));
					System.out.println("[MTRBR-DISPATCH] simulator null for action=" + message.action + " vehicle=" + message.vehicleId);
				}
			}
		});
		context.setPacketHandled(true);
	}

	private static void reply(ServerPlayer player, DispatcherActionPacket message, RouteRequestManager.DispatchResult result) {
		final var server = player.getServer();
		if (server == null) return;
		server.execute(() -> {
			if (server.getPlayerList().getPlayer(player.getUUID()) != player) return;
			final var text = net.minecraft.network.chat.Component.literal(RouteRequestManager.getVehicleCode(message.vehicleId) + ": ")
					.append(net.minecraft.network.chat.Component.translatable("message.mtr_brsignal_addon.dispatch." + result.reason()));
			if (!result.detail().isBlank()) text.append(" (" + result.detail() + ")");
			text.withStyle(result.accepted() ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED);
			player.sendSystemMessage(text);
			player.displayClientMessage(text, true);
		});
	}
}
