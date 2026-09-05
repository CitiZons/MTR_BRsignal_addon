package org.mtrbr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtrbr.MTRBR;

/** 复示信号方块实体：记录绑定的信号机位置。 */
public final class RepeatingSignalBlockEntity extends BlockEntity {

	private BlockPos boundSignalPos;

	public RepeatingSignalBlockEntity(BlockPos pos, BlockState state) {
		super(MTRBR.REPEATING_SIGNAL_BLOCK_ENTITY.get(), pos, state);
	}

	public BlockPos getBoundSignalPos() {
		return boundSignalPos;
	}

	public void setBoundSignalPos(BlockPos signalPos) {
		this.boundSignalPos = signalPos == null ? null : signalPos.immutable();
		setChanged();
	}

    @Override
    public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

	@Override
	protected void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		if (boundSignalPos != null) {
			tag.putInt("bx", boundSignalPos.getX());
			tag.putInt("by", boundSignalPos.getY());
			tag.putInt("bz", boundSignalPos.getZ());
		}
	}

	@Override
	public void load(CompoundTag tag) {
		super.load(tag);
		boundSignalPos = tag.contains("bx") ? new BlockPos(tag.getInt("bx"), tag.getInt("by"), tag.getInt("bz")) : null;
	}
}
