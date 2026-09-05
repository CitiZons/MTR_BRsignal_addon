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
 * LED 进路显示器方块（基础版：黑色屏幕）。
 * 显示图层由方块实体渲染器按绑定信号机开放的进路叠加。
 * 静态模型由 Blockbench 导出后替换 models/block/led_indicator.json。
 */
public final class LedIndicatorBlock extends Block implements EntityBlock {

	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty IS_22_5 = BooleanProperty.create("is_22_5");
	public static final BooleanProperty IS_45 = BooleanProperty.create("is_45");
	/**
	 * 16 个朝向（4 facing x 4 角度变体）对应的碰撞箱。
	 * 数值由模型各元素绕 (8,0,8) 旋转角度变体、再叠加 blockstate y 旋转（含原地反转 180°）后的世界 AABB 计算得到，
	 * 与渲染模型的外表面贴合。
	 */
	private static final VoxelShape[] SHAPES = {
			// NORTH
			Block.box(4, 0, 5, 12, 8.5, 14),
			Block.box(2.008, 0, 4.463, 12.461, 8.5, 15.074),
			Block.box(0.929, 0, 3.757, 12.243, 8.5, 15.071),
			Block.box(0.926, 0, 3.539, 11.537, 8.5, 13.992),
			// EAST
			Block.box(2, 0, 4, 11, 8.5, 12),
			Block.box(0.926, 0, 2.008, 11.537, 8.5, 12.461),
			Block.box(0.929, 0, 0.929, 12.243, 8.5, 12.243),
			Block.box(2.008, 0, 0.926, 12.461, 8.5, 11.537),
			// SOUTH
			Block.box(4, 0, 2, 12, 8.5, 11),
			Block.box(3.539, 0, 0.926, 13.992, 8.5, 11.537),
			Block.box(3.757, 0, 0.929, 15.071, 8.5, 12.243),
			Block.box(4.463, 0, 2.008, 15.074, 8.5, 12.461),
			// WEST
			Block.box(5, 0, 4, 14, 8.5, 12),
			Block.box(4.463, 0, 3.539, 15.074, 8.5, 13.992),
			Block.box(3.757, 0, 3.757, 15.071, 8.5, 15.071),
			Block.box(3.539, 0, 4.463, 13.992, 8.5, 15.074),
	};

	public LedIndicatorBlock(Properties properties) {
		super(properties);
		registerDefaultState(getStateDefinition().any().setValue(IndicatorMount.HANGING, false).setValue(FACING, Direction.NORTH).setValue(IS_22_5, false).setValue(IS_45, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(IndicatorMount.HANGING, FACING, IS_22_5, IS_45);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		final int quadrant = org.mtr.core.tool.Angle.getQuadrant(context.getRotation(), true);
		final Direction facing = Direction.from2DDataValue(quadrant / 4);
		return defaultBlockState().setValue(IndicatorMount.HANGING, IndicatorMount.placement(context)).setValue(FACING, facing).setValue(IS_22_5, quadrant % 2 == 1).setValue(IS_45, quadrant % 4 >= 2);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (IndicatorMount.isHanging(state)) return IndicatorMount.shape(state);
		final int facingIndex = switch (state.getValue(FACING)) {
			case NORTH -> 0;
			case EAST -> 1;
			case SOUTH -> 2;
			case WEST -> 3;
			default -> 0;
		};
		final int variantIndex = (state.getValue(IS_22_5) ? 1 : 0) + (state.getValue(IS_45) ? 2 : 0);
		return SHAPES[facingIndex * 4 + variantIndex];
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new LedIndicatorBlockEntity(pos, state);
	}
}
