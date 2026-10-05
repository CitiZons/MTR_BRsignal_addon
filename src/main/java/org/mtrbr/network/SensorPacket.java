package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.mtrbr.data.SensorConfig;
import org.mtrbr.data.SensorSavedData;
import org.mtrbr.server.SensorManager;
import java.util.function.Supplier;

/** Client request/read, or save settings while preserving server-owned bindings. */
public record SensorPacket(BlockPos pos, String settings) {
    public static void encode(SensorPacket m, FriendlyByteBuf b) { b.writeBlockPos(m.pos); b.writeUtf(m.settings, 32767); }
    public static SensorPacket decode(FriendlyByteBuf b) { return new SensorPacket(b.readBlockPos(), b.readUtf(32767)); }
    public static void handle(SensorPacket m, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> {
            var player = ctx.getSender();
            if (player == null) return;
            if (!(player.getMainHandItem().getItem() instanceof org.mtrbr.item.DebugToolItem)
                    && !(player.getOffhandItem().getItem() instanceof org.mtrbr.item.DebugToolItem)) return;
            var level = player.serverLevel();
            if (!level.hasChunkAt(m.pos) || player.distanceToSqr(m.pos.getX(), m.pos.getY(), m.pos.getZ()) > 4096
                    || !SensorManager.isSensor(level.getBlockState(m.pos))) return;
            var data = SensorSavedData.get(level); data.register(m.pos);
            if (!m.settings.isEmpty()) {
                if (!player.hasPermissions(2)) return;
                try {
                    SensorConfig incoming = SensorConfig.parse(m.settings), old = data.sensors.get(m.pos);
                    data.put(m.pos, new SensorConfig(incoming.name(), incoming.enabled(), incoming.pulse(), incoming.pulseTicks(), old.approach(), old.targets()));
                    SensorManager.reset(level, m.pos);
                } catch (RuntimeException e) { player.sendSystemMessage(Component.literal("Invalid sensor configuration")); return; }
            }
            Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SensorViewPacket(m.pos, data.sensors.get(m.pos).json(), player.hasPermissions(2)));
        });
        ctx.setPacketHandled(true);
    }
}
