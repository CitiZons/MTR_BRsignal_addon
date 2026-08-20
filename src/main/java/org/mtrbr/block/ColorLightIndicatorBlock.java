package org.mtrbr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 色灯式进路指示器方块（route 类型）。
 * 显示图层由方块实体渲染器按绑定信号机开放的 route 进路叠加。
 * 静态模型由 Blockbench 导出后替换 models/block/indicator_1.json。
 */
public final class ColorLightIndicatorBlock extends Block implements EntityBlock {

	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty IS_22_5 = BooleanProperty.create("is_22_5");
	public static final BooleanProperty IS_45 = BooleanProperty.create("is_45");
	private static final VoxelShape SHAPE = Block.box(4.5, 0, 5, 16, 11.5, 10);

	public ColorLightIndicatorBlock(Properties properties) {
		super(properties);
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(IS_22_5, false).setValue(IS_45, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, IS_22_5, IS_45);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		final int quadrant = org.mtr.core.tool.Angle.getQuadrant(context.getRotation(), true);
		final Direction facing = Direction.from2DDataValue(quadrant / 4);
		return defaultBlockState().setValue(FACING, facing).setValue(IS_22_5, quadrant % 2 == 1).setValue(IS_45, quadrant % 4 >= 2);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ColorLightIndicatorBlockEntity(pos, state);
	}
}
