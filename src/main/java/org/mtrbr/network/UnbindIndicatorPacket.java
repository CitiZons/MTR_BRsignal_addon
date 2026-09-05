package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.block.RepeatingSignalBlockEntity;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.data.RouteBindingsSavedData;
import org.mtrbr.web.WebTopologySnapshot;

import java.util.function.Supplier;

/** C2S：解除进路指示器与信号机的绑定。 */
public final class UnbindIndicatorPacket {

	private final BlockPos indicatorPos;

	public UnbindIndicatorPacket(BlockPos indicatorPos) {
		this.indicatorPos = indicatorPos;
	}

	public static void encode(UnbindIndicatorPacket message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.indicatorPos);
	}

	public static UnbindIndicatorPacket decode(FriendlyByteBuf buffer) {
		return new UnbindIndicatorPacket(buffer.readBlockPos());
	}

	public static void handle(UnbindIndicatorPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			if (context.getSender() != null && context.getSender().level() instanceof ServerLevel serverLevel
					&& PacketValidation.canEdit(context.getSender(), serverLevel, message.indicatorPos)
					&& PacketValidation.isIndicator(serverLevel, message.indicatorPos)) {
				final BlockEntity blockEntity = serverLevel.getBlockEntity(message.indicatorPos);
				if (blockEntity instanceof RepeatingSignalBlockEntity repeating) {
                    repeating.setBoundSignalPos(null);
                } else if (blockEntity instanceof LedIndicatorBlockEntity led) {
					led.setBoundSignalPos(null);
				} else if (blockEntity instanceof ColorLightIndicatorBlockEntity colorLight) {
					colorLight.setBoundSignalPos(null);
				}
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
				WebTopologySnapshot.invalidateTopology(serverLevel);
				data.removeIndicatorBinding(message.indicatorPos);
				if (blockEntity != null) {
					serverLevel.getServer().getPlayerList().broadcastAll(ClientboundBlockEntityDataPacket.create(blockEntity), serverLevel.dimension());
				}
				Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(),
						new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			}
		});
		context.setPacketHandled(true);
	}
}
