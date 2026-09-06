package org.mtrbr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtrbr.MTRBR;
import org.mtrbr.data.RouteBindingsSavedData;
import org.mtrbr.server.ServerAspectManager;

public final class PositionLightSignalBlockEntity extends BlockEntity {
    private BlockPos boundSignalPos;
    public PositionLightSignalBlockEntity(BlockPos pos, BlockState state) { super(MTRBR.POSITION_LIGHT_BLOCK_ENTITY.get(), pos, state); }
    public BlockPos getBoundSignalPos() { return boundSignalPos; }
    public void setBoundSignalPos(BlockPos pos) {
        if (java.util.Objects.equals(boundSignalPos, pos)) return;
        boundSignalPos = pos == null ? null : pos.immutable();
        setChanged();
        if (level instanceof ServerLevel server) {
            server.getServer().getPlayerList().broadcastAll(getUpdatePacket(), server.dimension());
        }
    }
    public void serverTick() {
        if (!(level instanceof ServerLevel server)) return;
        final BlockPos bound = RouteBindingsSavedData.get(server).getIndicatorBinding(worldPosition);
        if (!java.util.Objects.equals(boundSignalPos, bound)) setBoundSignalPos(bound);
        final boolean proceed = bound != null && ServerAspectManager.isShuntClear(server, bound);
        final BlockState state = getBlockState();
        if (state.getValue(PositionLightSignalBlock.PROCEED) != proceed) {
            server.setBlock(worldPosition, state.setValue(PositionLightSignalBlock.PROCEED, proceed), 3);
        }
    }
    @Override public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (boundSignalPos != null) tag.putLong("bound_signal", boundSignalPos.asLong());
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        boundSignalPos = tag.contains("bound_signal") ? BlockPos.of(tag.getLong("bound_signal")) : null;
    }
}
