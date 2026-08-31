package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.data.RouteBindingsSavedData;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/** C2S：设置信号机命名（空字符串表示清除）。命名规则：40 字符以内，字母/数字/下划线/连字符，不能以下划线或连字符开头。 */
public final class SetSignalNamePacket {

	private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_\\-]{0,39}$");

	private final BlockPos signalPos;
	private final String name;

	public SetSignalNamePacket(BlockPos signalPos, String name) {
		this.signalPos = signalPos;
		this.name = name;
	}

	public static void encode(SetSignalNamePacket message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.signalPos);
		buffer.writeUtf(message.name == null ? "" : message.name, 64);
	}

	public static SetSignalNamePacket decode(FriendlyByteBuf buffer) {
		return new SetSignalNamePacket(buffer.readBlockPos(), buffer.readUtf(64));
	}

	public static void handle(SetSignalNamePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			if (context.getSender() != null && context.getSender().level() instanceof ServerLevel serverLevel
					&& PacketValidation.canEdit(context.getSender(), serverLevel, message.signalPos)
					&& PacketValidation.isSignal(serverLevel, message.signalPos)) {
				final String trimmed = message.name == null ? "" : message.name.trim();
				if (!trimmed.isEmpty() && !NAME_PATTERN.matcher(trimmed).matches()) {
					return;
				}
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(serverLevel);
				data.setSignalName(message.signalPos, trimmed);
				org.mtrbr.server.ServerAspectManager.invalidateTopology(serverLevel);
				Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(),
						new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
			}
		});
		context.setPacketHandled(true);
	}
}
