package org.mtrbr.block;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared upright floor/ceiling mounting. No route or image is inverted. */
public final class IndicatorMount {
    public static final BooleanProperty HANGING = BooleanProperty.create("hanging");
    private IndicatorMount() {}
    public static boolean placement(BlockPlaceContext context) {
        if (context.getClickedFace() == Direction.DOWN) return true;
        if (context.getClickedFace() == Direction.UP) return false;
        final var level = context.getLevel();
        final var pos = context.getClickedPos();
        // Native MTR poles are narrow and are not full/sturdy faces.
        return isPole(level.getBlockState(pos.above())) && !isPole(level.getBlockState(pos.below()));
    }
    private static boolean isPole(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("mtr:signal_pole");
    }
    public static boolean isHanging(BlockState state) { return state.hasProperty(HANGING) && state.getValue(HANGING); }
    public static double offset(BlockState state) {
        return isHanging(state) ? IndicatorMountGeometry.offset(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()) : 0;
    }
    public static VoxelShape shape(BlockState state) {
        // The support itself is a quarter-block mounting plate.  Keep the
        // collision volume independent from the rendered 22.5/45 degree
        // variant; rotation changes the model, not the physical bracket.
        final Direction facing = state.getValue(LedIndicatorBlock.FACING);
        return switch (facing) {
            case NORTH -> Block.box(6, 6, 0, 10, 10, 4);
            case EAST -> Block.box(12, 6, 6, 16, 10, 10);
            case SOUTH -> Block.box(6, 6, 12, 10, 10, 16);
            case WEST -> Block.box(0, 6, 6, 4, 10, 10);
            default -> Block.box(6, 6, 0, 10, 10, 4);
        };
    }
}
