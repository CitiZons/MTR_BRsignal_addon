package org.mtrbr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.client.ServerAspectCache;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** S2C signal display snapshot; it carries no authority back to the server. */
public final class SyncSignalAspectsPacket {
	private final Map<ServerAspectCache.Key, ServerAspectCache.DisplayState> aspects;

	public SyncSignalAspectsPacket(Map<ServerAspectCache.Key, ServerAspectCache.DisplayState> aspects) {
		this.aspects = Map.copyOf(aspects);
	}

	public static void encode(SyncSignalAspectsPacket message, FriendlyByteBuf buffer) {
		buffer.writeInt(message.aspects.size());
		for (final Map.Entry<ServerAspectCache.Key, ServerAspectCache.DisplayState> entry : message.aspects.entrySet()) {
			buffer.writeBlockPos(entry.getKey().signalPos());
			buffer.writeBoolean(entry.getKey().reversed());
			buffer.writeByte(entry.getValue().aspect());
			buffer.writeUtf(entry.getValue().authorizationId(), 256);
			buffer.writeUtf(entry.getValue().routeContent(), 64);
			buffer.writeLong(entry.getValue().revision());
			buffer.writeBoolean(entry.getValue().nodePos() != null);
			if (entry.getValue().nodePos() != null) {
				buffer.writeBlockPos(entry.getValue().nodePos());
			}
		}
	}

	public static SyncSignalAspectsPacket decode(FriendlyByteBuf buffer) {
		final Map<ServerAspectCache.Key, ServerAspectCache.DisplayState> aspects = new HashMap<>();
		for (int i = 0, count = buffer.readInt(); i < count; i++) {
			aspects.put(new ServerAspectCache.Key(buffer.readBlockPos(), buffer.readBoolean()), new ServerAspectCache.DisplayState((int) buffer.readByte(), buffer.readUtf(256), buffer.readUtf(64), buffer.readLong(), buffer.readBoolean() ? buffer.readBlockPos() : null));
		}
		return new SyncSignalAspectsPacket(aspects);
	}

	public static void handle(SyncSignalAspectsPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> ServerAspectCache.replace(message.aspects));
		context.setPacketHandled(true);
	}
}
