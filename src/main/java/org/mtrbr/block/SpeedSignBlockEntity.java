package org.mtrbr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtrbr.SpeedSigns;
import org.mtrbr.data.SpeedSignText;

public final class SpeedSignBlockEntity extends BlockEntity {
    private SpeedSignText text;

    public SpeedSignBlockEntity(BlockPos pos, BlockState state) {
        super(SpeedSigns.ENTITY.get(), pos, state);
        text = defaults();
    }

    private SpeedSignText defaults() {
        return ((SpeedSignBlock) getBlockState().getBlock()).isDoubleLine()
                ? new SpeedSignText("30", "55") : new SpeedSignText("50", "");
    }

    public SpeedSignText text() { return text; }

    public boolean setText(String upper, String lower) {
        SpeedSignBlock block = (SpeedSignBlock) getBlockState().getBlock();
        if (block.isArrow()) return false;
        SpeedSignText validated = SpeedSignText.validate(upper, lower, block.isDoubleLine());
        if (validated == null) return false;
        text = validated;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Upper", text.upper());
        tag.putString("Lower", text.lower());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        SpeedSignText validated = SpeedSignText.validate(tag.getString("Upper"), tag.getString("Lower"),
                ((SpeedSignBlock) getBlockState().getBlock()).isDoubleLine());
        text = validated == null ? defaults() : validated;
    }

    @Override
    public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
