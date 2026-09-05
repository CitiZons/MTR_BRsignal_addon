package org.mtrbr.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/** Signal bracket with a horizontal facing so its model follows placement direction. */

public final class SignalBracketBlock extends Block {
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

	public SignalBracketBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.SOUTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
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
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}
}
