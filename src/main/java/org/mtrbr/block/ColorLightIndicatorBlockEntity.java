package org.mtrbr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtrbr.MTRBR;

/** 色灯式进路指示器方块实体：记录绑定的信号机位置。 */
public final class ColorLightIndicatorBlockEntity extends BlockEntity {

	private BlockPos boundSignalPos;

	public ColorLightIndicatorBlockEntity(BlockPos pos, BlockState state) {
		super(MTRBR.COLOR_LIGHT_INDICATOR_BLOCK_ENTITY.get(), pos, state);
	}

	public BlockPos getBoundSignalPos() {
		return boundSignalPos;
	}

	public void setBoundSignalPos(BlockPos signalPos) {
		this.boundSignalPos = signalPos == null ? null : signalPos.immutable();
		setChanged();
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
