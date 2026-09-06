package org.mtrbr.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.block.BlockSignalBase;

/** Signal bracket with a horizontal facing so its model follows placement direction. */

public final class SignalBracketBlock extends Block {
	public static final DirectionProperty FACING = DirectionHelper.FACING.data;
	public static final EnumProperty<BlockSignalBase.EnumBooleanInverted> IS_22_5 = BlockSignalBase.IS_22_5.data;
	public static final EnumProperty<BlockSignalBase.EnumBooleanInverted> IS_45 = BlockSignalBase.IS_45.data;

	public SignalBracketBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.SOUTH)
				.setValue(IS_22_5, BlockSignalBase.EnumBooleanInverted.FALSE)
				.setValue(IS_45, BlockSignalBase.EnumBooleanInverted.FALSE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, IS_22_5, IS_45);
	}

	@Override
	public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
		// The bracket is a centred quarter-block cube, placed against the rear
		// face relative to its facing direction.  Keep this collision shape
		// independent from the rendered model rotation.
		return switch (state.getValue(FACING)) {
			case NORTH -> Block.box(6, 6, 0, 10, 10, 4);
			case EAST -> Block.box(12, 6, 6, 16, 10, 10);
			case SOUTH -> Block.box(6, 6, 12, 10, 10, 16);
			case WEST -> Block.box(0, 6, 6, 4, 10, 10);
			default -> Block.box(6, 6, 0, 10, 10, 4);
		};
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		final int quadrant = org.mtr.core.tool.Angle.getQuadrant(context.getRotation(), true);
		final Direction facing = Direction.from2DDataValue(quadrant / 4);
		return defaultBlockState().setValue(FACING, facing)
				.setValue(IS_22_5, quadrant % 2 == 1 ? BlockSignalBase.EnumBooleanInverted.TRUE : BlockSignalBase.EnumBooleanInverted.FALSE)
				.setValue(IS_45, quadrant % 4 >= 2 ? BlockSignalBase.EnumBooleanInverted.TRUE : BlockSignalBase.EnumBooleanInverted.FALSE);
	}
}
