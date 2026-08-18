package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.data.RouteBindingsSavedData;

import java.util.function.Supplier;

/** C2S：把 LED 进路显示器绑定到某个信号机。 */
public final class BindIndicatorPacket {

	private final BlockPos indicatorPos;
	private final BlockPos signalPos;

	public BindIndicatorPacket(BlockPos indicatorPos, BlockPos signalPos) {
		this.indicatorPos = indicatorPos;
		this.signalPos = signalPos;
	}

	public static void encode(BindIndicatorPacket message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.indicatorPos);
		buffer.writeBlockPos(message.signalPos);
	}

	public static BindIndicatorPacket decode(FriendlyByteBuf buffer) {
		return new BindIndicatorPacket(buffer.readBlockPos(), buffer.readBlockPos());
	}

	public static void handle(BindIndicatorPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			if (context.getSender() != null && context.getSender().level() instanceof ServerLevel serverLevel) {
				final net.minecraft.world.level.block.entity.BlockEntity blockEntity = serverLevel.getBlockEntity(message.indicatorPos);
				if (blockEntity instanceof LedIndicatorBlockEntity led) {
					led.setBoundSignalPos(message.signalPos);
				} else if (blockEntity instanceof ColorLightIndicatorBlockEntity colorLight) {
					colorLight.setBoundSignalPos(message.signalPos);
				}
				// 双写 SavedData，确保重进游戏后绑定不丢失
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
				data.setIndicatorBinding(message.indicatorPos, message.signalPos);
				// 显式广播方块实体数据包，确保客户端同步（sendBlockUpdated 在状态未变化时可能不发）
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
