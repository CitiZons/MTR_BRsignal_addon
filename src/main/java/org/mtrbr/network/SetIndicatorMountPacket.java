package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.block.IndicatorMount;
import java.util.function.Supplier;

/** C2S, operator-validated floor/ceiling installation change. */
public record SetIndicatorMountPacket(BlockPos pos, boolean hanging) {
    public static void encode(SetIndicatorMountPacket m, FriendlyByteBuf b) { b.writeBlockPos(m.pos); b.writeBoolean(m.hanging); }
    public static SetIndicatorMountPacket decode(FriendlyByteBuf b) { return new SetIndicatorMountPacket(b.readBlockPos(),b.readBoolean()); }
    public static void handle(SetIndicatorMountPacket m, Supplier<NetworkEvent.Context> supplier) {
        var context=supplier.get();
        context.enqueueWork(() -> {
            var player=context.getSender();
            if (player==null) return;
            var level=player.serverLevel();
            if (!PacketValidation.canEdit(player,level,m.pos) || !PacketValidation.isIndicator(level,m.pos)) return;
            var state=level.getBlockState(m.pos);
            if (state.hasProperty(IndicatorMount.HANGING)) level.setBlock(m.pos,state.setValue(IndicatorMount.HANGING,m.hanging),3);
        });
        context.setPacketHandled(true);
    }
}
