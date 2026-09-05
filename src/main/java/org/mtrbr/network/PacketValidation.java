package org.mtrbr.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtrbr.block.ColorLightIndicatorBlockEntity;
import org.mtrbr.block.LedIndicatorBlockEntity;
import org.mtrbr.block.RepeatingSignalBlockEntity;

/** Minimal server-side validation for privileged signal configuration packets. */
final class PacketValidation {
	private static final double MAX_EDIT_DISTANCE_SQUARED = 64 * 64;

	private PacketValidation() {
	}

	static boolean canEdit(ServerPlayer player, ServerLevel level, BlockPos position) {
		return player != null && player.hasPermissions(2) && player.level() == level
				&& level.hasChunkAt(position) && player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) <= MAX_EDIT_DISTANCE_SQUARED;
	}

	static boolean isSignal(ServerLevel level, BlockPos position) {
		return level.getBlockState(position).getBlock() instanceof BlockSignalBase
				&& level.getBlockEntity(position) instanceof BlockSignalBase.BlockEntityBase;
	}

	static boolean isNode(ServerLevel level, BlockPos position) {
		return level.getBlockState(position).getBlock() instanceof BlockNode;
	}

	static boolean isIndicator(ServerLevel level, BlockPos position) {
		final BlockEntity blockEntity = level.getBlockEntity(position);
		return blockEntity instanceof RepeatingSignalBlockEntity || blockEntity instanceof LedIndicatorBlockEntity || blockEntity instanceof ColorLightIndicatorBlockEntity;
	}
}
