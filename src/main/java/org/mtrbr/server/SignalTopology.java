package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtrbr.data.NodeBinding;
import org.mtrbr.data.RouteBindingsSavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compiles persisted bindings into a server-only SignalFace snapshot. */
public final class SignalTopology {
	private SignalTopology() {
	}

	public static Map<String, SignalFace> build(ServerLevel level) {
		final Map<String, SignalFace> faces = new LinkedHashMap<>();
		final RouteBindingsSavedData bindings = RouteBindingsSavedData.get(level);
		final Map<BlockPos, NodeBinding> nodeBindings = bindings.getNodeBindings();
		final java.util.Set<BlockPos> signalPositions = new java.util.LinkedHashSet<>(ServerSignalRegistry.getSignals(level));
		signalPositions.addAll(bindings.getManagedSignalPositions());
		for (final BlockPos configuredPos : signalPositions) {
			final BlockPos signalPos = configuredPos.immutable();
			final BlockState state = level.getBlockState(signalPos);
			if (!(state.getBlock() instanceof BlockSignalBase)) {
				continue;
			}
			final NodeBinding binding = nodeBindings.get(signalPos);
			final BlockPos nodePos = binding != null && binding.node() != null ? binding.node().immutable() : findAppliedNode(level, signalPos);
			if (nodePos == null) {
				continue;
			}
			final float directionOffset = binding != null && binding.reversed() ? 180 : 0;
			final float frontTravelAngle = getSignalAngle(state) + 90 + directionOffset;
			addFace(faces, signalPos, nodePos, false, frontTravelAngle);
			if (level.getBlockEntity(signalPos) instanceof BlockSignalBase.BlockEntityBase entity && entity.isDoubleSided) {
				addFace(faces, signalPos, nodePos, true, frontTravelAngle + 180);
			}
		}
		return Map.copyOf(faces);
	}

	private static void addFace(Map<String, SignalFace> faces, BlockPos signalPos, BlockPos nodePos, boolean backSide, float travelAngle) {
		final String id = id(signalPos, backSide);
		faces.put(id, new SignalFace(id, signalPos, nodePos, backSide, travelAngle));
	}

	/** Mirrors MTR's RenderSignalBase node search without using client state. */
	private static BlockPos findAppliedNode(ServerLevel level, BlockPos signalPos) {
		final Direction facing = Direction.fromYRot(getSignalAngle(level.getBlockState(signalPos)));
		int closestDistance = Integer.MAX_VALUE;
		BlockPos closestPos = null;
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				for (int y = -5; y <= 5; y++) {
					final BlockPos checkPos = signalPos.above(y).relative(facing.getClockWise(), x).relative(facing, z);
					if (level.getBlockState(checkPos).getBlock() instanceof BlockNode) {
						final int distance = checkPos.distManhattan(signalPos);
						if (distance < closestDistance) {
							closestDistance = distance;
							closestPos = checkPos.immutable();
						}
					}
				}
			}
		}
		return closestPos;
	}

	private static float getSignalAngle(BlockState state) {
		final Direction facing = state.getValue(DirectionHelper.FACING.data);
		final boolean is22_5 = state.getValue(BlockSignalBase.IS_22_5.data).booleanValue;
		final boolean is45 = state.getValue(BlockSignalBase.IS_45.data).booleanValue;
		return facing.toYRot() + (is22_5 ? 22.5F : 0) + (is45 ? 45 : 0);
	}

	public static String id(BlockPos signalPos, boolean reversed) {
		return signalPos.getX() + "," + signalPos.getY() + "," + signalPos.getZ() + ":" + (reversed ? "reverse" : "forward");
	}
}
