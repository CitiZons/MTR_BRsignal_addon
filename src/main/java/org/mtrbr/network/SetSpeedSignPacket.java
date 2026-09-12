package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import org.mtrbr.block.SpeedSignBlock;
import org.mtrbr.block.SpeedSignBlockEntity;
import org.mtrbr.block.SpeedSignMount;
import org.mtrbr.block.TextSignPoleMount;
import org.mtrbr.data.SpeedSignText;

import java.util.function.Supplier;

public record SetSpeedSignPacket(BlockPos pos, String upper, String lower, SpeedSignMount mount, TextSignPoleMount poleMount) {
    public SetSpeedSignPacket(BlockPos pos, String upper, String lower, SpeedSignMount mount) { this(pos, upper, lower, mount, TextSignPoleMount.CENTER); }
    public static void encode(SetSpeedSignPacket message, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(message.pos);
        buffer.writeUtf(message.upper, SpeedSignText.MAX_LENGTH);
        buffer.writeUtf(message.lower, SpeedSignText.MAX_LENGTH);
        buffer.writeEnum(message.mount);
        buffer.writeEnum(message.poleMount);
    }

    public static SetSpeedSignPacket decode(FriendlyByteBuf buffer) {
        return new SetSpeedSignPacket(buffer.readBlockPos(), buffer.readUtf(SpeedSignText.MAX_LENGTH),
                buffer.readUtf(SpeedSignText.MAX_LENGTH), buffer.readEnum(SpeedSignMount.class), buffer.readEnum(TextSignPoleMount.class));
    }

    public static void handle(SetSpeedSignPacket message, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var level = player.serverLevel();
            if (!PacketValidation.canEdit(player, level, message.pos)) return;
            var state = level.getBlockState(message.pos);
            if (!(state.getBlock() instanceof SpeedSignBlock block)
                    || !(level.getBlockEntity(message.pos) instanceof SpeedSignBlockEntity entity)) return;
            if (!message.mount.allows(block.isArrow())) return;
            if (!block.isArrow() && !entity.setText(message.upper, message.lower)) {
                player.displayClientMessage(Component.translatable("screen.mtr_brsignal_addon.speed_sign.invalid"), false);
                return;
            }
            var updated = SpeedSignBlock.withMount(state, message.mount);
            if (block.isTextSign()) updated = updated.setValue(SpeedSignBlock.TEXT_POLE, message.poleMount);
            level.setBlock(message.pos, updated, 3);
            SpeedSignBlock.alignCompanion(level, message.pos, updated);
        });
        context.setPacketHandled(true);
    }
}
