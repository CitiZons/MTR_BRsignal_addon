package org.mtrbr.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.mtrbr.MTRBR;

public final class PositionLightSignalBlock extends Block implements EntityBlock {
    public static final BooleanProperty PROCEED = BooleanProperty.create("proceed");
    private final boolean yellow;

    public PositionLightSignalBlock(Properties properties, boolean yellow) {
        super(properties);
        this.yellow = yellow;
        registerDefaultState(defaultBlockState().setValue(RepeatingSignalBlock.FACING, Direction.NORTH)
            .setValue(RepeatingSignalBlock.IS_22_5, false).setValue(RepeatingSignalBlock.IS_45, false)
            .setValue(IndicatorMount.HANGING, false).setValue(PROCEED, false));
    }

    public boolean isYellow() { return yellow; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RepeatingSignalBlock.FACING, RepeatingSignalBlock.IS_22_5, RepeatingSignalBlock.IS_45,
            IndicatorMount.HANGING, PROCEED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        final int quadrant = org.mtr.core.tool.Angle.getQuadrant(context.getRotation(), true);
        return defaultBlockState().setValue(RepeatingSignalBlock.FACING, Direction.from2DDataValue(quadrant / 4))
            .setValue(RepeatingSignalBlock.IS_22_5, quadrant % 2 == 1).setValue(RepeatingSignalBlock.IS_45, quadrant % 4 >= 2)
            .setValue(IndicatorMount.HANGING, IndicatorMount.placement(context));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        final int facing = switch (state.getValue(RepeatingSignalBlock.FACING)) {
            case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0;
        };
        final int angle = (state.getValue(RepeatingSignalBlock.IS_22_5) ? 1 : 0) + (state.getValue(RepeatingSignalBlock.IS_45) ? 2 : 0);
        return PositionLightSignalGeometry.shape(facing * 4 + angle, IndicatorMount.isHanging(state));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PositionLightSignalBlockEntity(pos, state); }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != MTRBR.POSITION_LIGHT_BLOCK_ENTITY.get()) return null;
        return (world, pos, current, entity) -> ((PositionLightSignalBlockEntity) entity).serverTick();
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (state.getBlock() != next.getBlock()) {
            if (level instanceof net.minecraft.server.level.ServerLevel server) {
                final var data = org.mtrbr.data.RouteBindingsSavedData.get(server);
                data.clearIndicatorBinding(pos);
                org.mtrbr.network.Network.CHANNEL.send(net.minecraftforge.network.PacketDistributor.DIMENSION.with(server::dimension),
                    new org.mtrbr.network.SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
