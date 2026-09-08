package org.mtrbr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Private reply to a player's explicit generate-and-open request. */
public record OpenWebTokenPacket(UUID requestId, String url) {
	public static void encode(OpenWebTokenPacket message, FriendlyByteBuf buffer) {
		buffer.writeUUID(message.requestId());
		buffer.writeUtf(message.url(), 2048);
	}

	public static OpenWebTokenPacket decode(FriendlyByteBuf buffer) {
		return new OpenWebTokenPacket(buffer.readUUID(), buffer.readUtf(2048));
	}

	public static void handle(OpenWebTokenPacket message, Supplier<NetworkEvent.Context> supplier) {
		final NetworkEvent.Context context = supplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> () -> org.mtrbr.client.ClientWebTokenActions.open(message.requestId(), message.url())));
		context.setPacketHandled(true);
	}
}
