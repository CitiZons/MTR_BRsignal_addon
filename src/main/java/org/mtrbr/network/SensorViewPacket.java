package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import java.util.function.Supplier;

public record SensorViewPacket(BlockPos pos, String settings, boolean editable) {
    public static void encode(SensorViewPacket m, FriendlyByteBuf b) { b.writeBlockPos(m.pos); b.writeUtf(m.settings, 1048576); b.writeBoolean(m.editable); }
    public static SensorViewPacket decode(FriendlyByteBuf b) { return new SensorViewPacket(b.readBlockPos(), b.readUtf(1048576), b.readBoolean()); }
    public static void handle(SensorViewPacket m, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> org.mtrbr.client.ClientHooks.openSensorScreen(m)));
        ctx.setPacketHandled(true);
    }
}
