package org.mtrbr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class SpeedSignBlock extends Block implements EntityBlock {
    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    public static final BooleanProperty HANGING = BooleanProperty.create("hanging");
    public static final BooleanProperty CENTERED = BooleanProperty.create("centered");
    public static final EnumProperty<TextSignPoleMount> TEXT_POLE = EnumProperty.create("text_pole", TextSignPoleMount.class);
    private final boolean warning;
    private final boolean doubleLine;
    private final String arrow;
    private final boolean textSign;

    public SpeedSignBlock(Properties properties, boolean warning, boolean doubleLine, String arrow) {
        this(properties, warning, doubleLine, arrow, false);
    }
    public SpeedSignBlock(Properties properties, boolean warning, boolean doubleLine, String arrow, boolean textSign) {
        super(properties);
        this.warning = warning;
        this.doubleLine = doubleLine;
        this.arrow = arrow;
        this.textSign = textSign;
        registerDefaultState(defaultBlockState().setValue(ROTATION, 0).setValue(HANGING, false).setValue(CENTERED, false).setValue(TEXT_POLE, TextSignPoleMount.CENTER));
    }

    public boolean isWarning() { return warning; }
    public boolean isDoubleLine() { return doubleLine; }
    public boolean isArrow() { return !arrow.isEmpty(); }
    public String arrow() { return arrow; }
    public boolean isTextSign() { return textSign; }
    public static float angle(BlockState state) { return state.getValue(ROTATION) * 22.5F; }

    public static SpeedSignMount mount(BlockState state) {
        boolean arrow = ((SpeedSignBlock) state.getBlock()).isArrow();
        if (!arrow && state.getValue(CENTERED)) return SpeedSignMount.CENTER;
        // Keep the legacy hanging property so existing worlds retain their plate positions.
        return state.getValue(HANGING) == arrow ? SpeedSignMount.TOP : SpeedSignMount.BOTTOM;
    }

    public static BlockState withMount(BlockState state, SpeedSignMount mount) {
        boolean arrow = ((SpeedSignBlock) state.getBlock()).isArrow();
        if (!mount.allows(arrow)) return state;
        return state.setValue(CENTERED, mount == SpeedSignMount.CENTER)
                .setValue(HANGING, (mount == SpeedSignMount.TOP) == arrow);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROTATION, HANGING, CENTERED, TEXT_POLE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        int rotation = Math.floorMod(Math.round(context.getRotation() / 22.5F), 16);
        BlockState state = defaultBlockState().setValue(ROTATION, rotation)
                .setValue(HANGING, context.getClickedFace() == Direction.DOWN);
        for (Direction side : new Direction[]{Direction.UP, Direction.DOWN}) {
            BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(side));
            if (isCompanion(state, neighbor)) {
                return withMount(state, side == Direction.UP ? SpeedSignMount.TOP : SpeedSignMount.BOTTOM)
                        .setValue(ROTATION, neighbor.getValue(ROTATION));
            }
        }
        return state;
    }

    private static boolean isCompanion(BlockState state, BlockState other) {
        return other.getBlock() instanceof SpeedSignBlock neighbor
                && neighbor.isArrow() != ((SpeedSignBlock) state.getBlock()).isArrow();
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        alignCompanion(level, pos, state);
    }

    /** Match the neighbor at the selected block boundary; CENTER is an explicit standalone position. */
    public static void alignCompanion(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide || mount(state) == SpeedSignMount.CENTER) return;
        boolean top = mount(state) == SpeedSignMount.TOP;
        BlockPos otherPos = top ? pos.above() : pos.below();
        BlockState other = level.getBlockState(otherPos);
        if (isCompanion(state, other)) {
            level.setBlock(otherPos, withMount(other, top ? SpeedSignMount.BOTTOM : SpeedSignMount.TOP)
                    .setValue(ROTATION, state.getValue(ROTATION)), 3);
        }
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(ROTATION, rotation.rotate(state.getValue(ROTATION), 16));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(ROTATION, mirror.mirror(state.getValue(ROTATION), 16));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new SpeedSignBlockEntity(pos, state); }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(isArrow(), mount(state), state.getValue(ROTATION));
    }

    public static VoxelShape shape(boolean arrow, SpeedSignMount mount, int rotation) {
        double bottom = mount.bottom(arrow);
        double top = bottom + (arrow ? 0.25 : 0.75);
        double radians = Math.toRadians(-rotation * 22.5);
        double poleHalf = 0.125 * (Math.abs(Math.sin(radians)) + Math.abs(Math.cos(radians)));
        double poleHeight = mount.poleHeight(arrow);
        VoxelShape result = Shapes.box(0.5-poleHalf, 0, 0.5-poleHalf, 0.5+poleHalf, poleHeight, 0.5+poleHalf);
        // Enclose the full depth from the plate front to the rear of the pole.
        for (int i = 0; i < 24; i++) {
            double x0 = -0.375 + i / 32.0, x1 = x0 + 1 / 32.0;
            double minX = 1, maxX = 0, minZ = 1, maxZ = 0;
            for (double x : new double[]{x0, x1}) for (double z : new double[]{-7.0 / 48, 0.125}) {
                double rx = 0.5 + x * Math.cos(radians) + z * Math.sin(radians);
                double rz = 0.5 - x * Math.sin(radians) + z * Math.cos(radians);
                minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
                minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
            }
            result = Shapes.or(result, Shapes.box(minX, bottom, minZ, maxX, top, maxZ));
        }
        return result;
    }
}
