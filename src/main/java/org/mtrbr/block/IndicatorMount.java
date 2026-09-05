package org.mtrbr.block;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
        final int facingIndex = switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        final int variantIndex = variantIndex(state);
        final int orientationIndex = facingIndex * 4 + variantIndex + (isHanging(state) ? 16 : 0);
        final String model = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return IndicatorMountGeometry.shape(model, orientationIndex);
    }

    private static int variantIndex(BlockState state) {
        if (state.getBlock() instanceof RepeatingSignalBlock) {
            return (state.getValue(RepeatingSignalBlock.IS_22_5) ? 1 : 0)
                    + (state.getValue(RepeatingSignalBlock.IS_45) ? 2 : 0);
        }
        if (state.getBlock() instanceof LedIndicatorBlock) {
            return (state.getValue(LedIndicatorBlock.IS_22_5) ? 1 : 0)
                    + (state.getValue(LedIndicatorBlock.IS_45) ? 2 : 0);
        }
        if (state.getBlock() instanceof ColorLightIndicatorBlock) {
            return (state.getValue(ColorLightIndicatorBlock.IS_22_5) ? 1 : 0)
                    + (state.getValue(ColorLightIndicatorBlock.IS_45) ? 2 : 0);
        }
        return 0;
    }
}
