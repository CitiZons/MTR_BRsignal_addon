package org.mtrbr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.client.ClientDispatcherData;
import org.mtrbr.server.RouteRequestManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** S2C 调度请求快照。 */
public final class SyncDispatcherDataPacket {
	private final List<RouteRequestManager.RequestSnapshot> requests;

	public SyncDispatcherDataPacket(List<RouteRequestManager.RequestSnapshot> requests) {
		this.requests = List.copyOf(requests);
	}

	public static void encode(SyncDispatcherDataPacket message, FriendlyByteBuf buffer) {
		buffer.writeInt(message.requests.size());
		for (final RouteRequestManager.RequestSnapshot request : message.requests) {
			buffer.writeLong(request.vehicleId());
			buffer.writeUtf(request.vehicleCode(), 16);
			buffer.writeUtf(request.state().name(), 32);
			buffer.writeDouble(request.head());
			buffer.writeDouble(request.controlDistance());
			buffer.writeDouble(request.endDistance());
			buffer.writeDouble(request.authorizationEndDistance());
			buffer.writeBoolean(request.authorized());
			buffer.writeBoolean(request.oneShotOverride());
			buffer.writeDouble(request.speedKmh());
			buffer.writeUtf(request.routeName(), 128);
			buffer.writeUtf(request.destination(), 128);
			buffer.writeUtf(request.nextStation(), 128);
			buffer.writeInt(request.occupiedBlocks());
			buffer.writeInt(request.authorizedBlocks());
			buffer.writeInt(request.lockedBlocks());
		}
	}

	public static SyncDispatcherDataPacket decode(FriendlyByteBuf buffer) {
		final List<RouteRequestManager.RequestSnapshot> requests = new ArrayList<>();
		for (int i = 0, count = buffer.readInt(); i < count; i++) {
			requests.add(new RouteRequestManager.RequestSnapshot(buffer.readLong(), buffer.readUtf(16), org.mtrbr.server.RequestState.valueOf(buffer.readUtf(32)), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readBoolean(), buffer.readBoolean(), buffer.readDouble(), buffer.readUtf(128), buffer.readUtf(128), buffer.readUtf(128), buffer.readInt(), buffer.readInt(), buffer.readInt()));
		}
		return new SyncDispatcherDataPacket(requests);
	}

	public static void handle(SyncDispatcherDataPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
		final NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> ClientDispatcherData.replace(message.requests.stream()
				.map(request -> new ClientDispatcherData.Entry(request.vehicleId(), request.vehicleCode(), request.state().name(), request.oneShotOverride(), request.head(), request.controlDistance(), request.endDistance(), request.authorizationEndDistance(), request.authorized(), request.speedKmh(), request.routeName(), request.destination(), request.nextStation(), request.occupiedBlocks(), request.authorizedBlocks(), request.lockedBlocks()))
				.toList()));
		context.setPacketHandled(true);
	}
}
