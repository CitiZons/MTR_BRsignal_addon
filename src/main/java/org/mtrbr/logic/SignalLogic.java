package org.mtrbr.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.core.data.Position;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.client.MinecraftClientData;
import org.mtrbr.block.ColorLightIndicatorBlock;
import org.mtrbr.block.LedIndicatorBlock;
import org.mtrbr.block.RepeatingSignalBlock;
import org.mtrbr.client.ServerAspectCache;

/**
 * 纯客户端显示工具层。
 *
 * 服务端 SectionState / RouteRequest / Authorization 是唯一闭塞权威；客户端不得参与
 * 占用、FCFS、Authorization 或停车判断。Aspect 只读取 {@link ServerAspectCache}，
 * 缓存缺失时返回红灯。
 */
public final class SignalLogic {

	private SignalLogic() {
	}

	/** 该方块状态是否为 MTR 信号机。 */
	public static boolean isSignalBlock(BlockState state) {
		return state.getBlock() instanceof BlockSignalBase;
	}

	/** 该方块状态是否为 MTR 轨道节点。 */
	public static boolean isNodeBlock(BlockState state) {
		return state.getBlock() instanceof BlockNode;
	}

	/** 该方块状态是否为本 mod 的 LED 或色灯进路显示器。 */
	public static boolean isIndicatorBlock(BlockState state) {
		return state.getBlock() instanceof LedIndicatorBlock
				|| state.getBlock() instanceof ColorLightIndicatorBlock
                || state.getBlock() instanceof RepeatingSignalBlock;
	}

	/** 是否为 LED 进路显示器。 */
	public static boolean isLedIndicatorBlock(BlockState state) {
		return state.getBlock() instanceof LedIndicatorBlock;
	}

	/** 是否为色灯式进路指示器。 */
	public static boolean isColorLightIndicatorBlock(BlockState state) {
		return state.getBlock() instanceof ColorLightIndicatorBlock;
	}

	/** 进路指示器（LED/色灯）总朝向角：facing + 22.5/45 偏移。 */
	public static float getIndicatorAngle(BlockState state) {
		if (state.getBlock() instanceof LedIndicatorBlock) {
			return state.getValue(LedIndicatorBlock.FACING).toYRot()
					+ (state.getValue(LedIndicatorBlock.IS_22_5) ? 22.5F : 0)
					+ (state.getValue(LedIndicatorBlock.IS_45) ? 45 : 0);
		}
		if (state.getBlock() instanceof ColorLightIndicatorBlock || state.getBlock() instanceof RepeatingSignalBlock) {
			return state.getValue(ColorLightIndicatorBlock.FACING).toYRot()
					+ (state.getValue(ColorLightIndicatorBlock.IS_22_5) ? 22.5F : 0)
					+ (state.getValue(ColorLightIndicatorBlock.IS_45) ? 45 : 0);
		}
		return 0;
	}

	/** 复刻 BlockSignalBase.getAngle：朝向 + 22.5/45 偏移。 */
	public static float getSignalAngle(BlockState state) {
		if (!isSignalBlock(state)) {
			return 0;
		}
		final Direction facing = state.getValue(DirectionHelper.FACING.data);
		final boolean is22_5 = state.getValue(BlockSignalBase.IS_22_5.data).booleanValue;
		final boolean is45 = state.getValue(BlockSignalBase.IS_45.data).booleanValue;
		return facing.toYRot() + (is22_5 ? 22.5F : 0) + (is45 ? 45 : 0);
	}

	/**
	 * 该节点处、与信号机朝向最接近的轨道方向（单位向量 dx,dz）。
	 * 用于在轨道上绘制绑定方向箭头。
	 */
	public static double[] getTrackDirection(Level level, BlockPos signalPos, BlockPos nodePos) {
		final float signalAngle = getSignalAngle(level.getBlockState(signalPos));
		// MTR 信号机正面约定：列车行进方向 = 方块朝向 + 90°。
		final float travelAngle = signalAngle - 90;
		final MinecraftClientData clientData = MinecraftClientData.getInstance();
		final Position startPosition = new Position(nodePos.getX(), nodePos.getY(), nodePos.getZ());
		final double[] best = {Double.MAX_VALUE, 1, 0};
		final boolean[] found = {false};
		clientData.positionsToRail.getOrDefault(startPosition, new Object2ObjectOpenHashMap<>()).forEach((endPosition, rail) -> {
			final double railAngle = Math.toDegrees(Math.atan2(endPosition.getZ() - nodePos.getZ(), endPosition.getX() - nodePos.getX()));
			final double difference = Math.abs(circularDifference(railAngle, travelAngle));
			if (difference < best[0]) {
				best[0] = difference;
				best[1] = endPosition.getX() - nodePos.getX();
				best[2] = endPosition.getZ() - nodePos.getZ();
				found[0] = true;
			}
		});
		if (!found[0]) {
			return null;
		}
		final double length = Math.sqrt(best[1] * best[1] + best[2] * best[2]);
		if (length < 1.0E-4) {
			return null;
		}
		return new double[]{best[1] / length, best[2] / length};
	}

	/** Aspect 只读服务端同步缓存；缓存缺失默认红灯，不做客户端闭塞推算。 */
	public static int getSignalAspect(Level level, BlockPos signalPos, BlockEntity blockEntity, boolean isBackSide) {
		final Integer serverAspect = ServerAspectCache.get(signalPos, isBackSide);
		return serverAspect == null ? 1 : serverAspect;
	}

	public static String getAspectColorName(int aspect) {
		return switch (aspect) {
			case 1 -> "红";
			case 2 -> "黄";
			case 3 -> "双黄";
			default -> "绿";
		};
	}

	private static double circularDifference(double a, double b) {
		double difference = (a - b) % 360;
		if (difference < -180) {
			difference += 360;
		}
		if (difference > 180) {
			difference -= 360;
		}
		return Math.abs(difference);
	}
}
