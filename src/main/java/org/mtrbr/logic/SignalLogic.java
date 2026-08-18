package org.mtrbr.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtrbr.client.SignalCache;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.NodeBinding;
import org.mtrbr.data.RouteBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 信号判定“拦截/读取”层。
 *
 * 读取 MTR 的轨道/占用数据，并实现 BR 闭塞链 aspect：
 * 本区段占用或未开放进路 -> 红；下一信号红 -> 单黄；下一信号单黄 -> 双黄；其余 -> 绿。
 * 最多向后传递 {@link #MAX_CHAIN_DEPTH} 个信号。
 */
public final class SignalLogic {

	/** 信号最多向后传递的数量（红-单黄-双黄-绿，共 4 个）。 */
	public static final int MAX_CHAIN_DEPTH = 4;

	private static final Map<Long, Integer> ASPECT_CACHE = new HashMap<>();
	private static long lastAspectCacheTime = -1;
	private static final java.lang.reflect.Field RAIL_PROGRESS_FIELD = initRailProgressField();

	private SignalLogic() {
	}

	private static java.lang.reflect.Field initRailProgressField() {
		try {
			final java.lang.reflect.Field field = Vehicle.class.getSuperclass().getDeclaredField("railProgress");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	/** 读取列车沿进路累计的头部里程（MTR 的 railProgress 为 protected，用反射读取；失败返回 -1）。 */
	private static double getRailProgress(VehicleExtension vehicle) {
		if (RAIL_PROGRESS_FIELD == null) {
			return -1;
		}
		try {
			return RAIL_PROGRESS_FIELD.getDouble(vehicle);
		} catch (ReflectiveOperationException ignored) {
			return -1;
		}
	}

	/** 该方块状态是否为 MTR 信号机。 */
	public static boolean isSignalBlock(BlockState state) {
		return state.getBlock() instanceof BlockSignalBase;
	}

	/** 该方块状态是否为 MTR 轨道节点。 */
	public static boolean isNodeBlock(BlockState state) {
		return state.getBlock() instanceof BlockNode;
	}

	/** 该方块状态是否为本 mod 的 LED 进路显示器。 */
	public static boolean isIndicatorBlock(BlockState state) {
		return state.getBlock() instanceof org.mtrbr.block.LedIndicatorBlock
				|| state.getBlock() instanceof org.mtrbr.block.ColorLightIndicatorBlock;
	}

	/** 是否为 LED 进路显示器。 */
	public static boolean isLedIndicatorBlock(BlockState state) {
		return state.getBlock() instanceof org.mtrbr.block.LedIndicatorBlock;
	}

	/** 是否为色灯式进路指示器。 */
	public static boolean isColorLightIndicatorBlock(BlockState state) {
		return state.getBlock() instanceof org.mtrbr.block.ColorLightIndicatorBlock;
	}

	/** 进路指示器（LED/色灯）总朝向角：facing + 22.5/45 偏移。 */
	public static float getIndicatorAngle(BlockState state) {
		if (state.getBlock() instanceof org.mtrbr.block.LedIndicatorBlock) {
			return state.getValue(org.mtrbr.block.LedIndicatorBlock.FACING).toYRot()
					+ (state.getValue(org.mtrbr.block.LedIndicatorBlock.IS_22_5) ? 22.5F : 0)
					+ (state.getValue(org.mtrbr.block.LedIndicatorBlock.IS_45) ? 45 : 0);
		}
		if (state.getBlock() instanceof org.mtrbr.block.ColorLightIndicatorBlock) {
			return state.getValue(org.mtrbr.block.ColorLightIndicatorBlock.FACING).toYRot()
					+ (state.getValue(org.mtrbr.block.ColorLightIndicatorBlock.IS_22_5) ? 22.5F : 0)
					+ (state.getValue(org.mtrbr.block.ColorLightIndicatorBlock.IS_45) ? 45 : 0);
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

	/** 信号机作用的轨道节点：优先手动绑定，否则复刻 RenderSignalBase.getNodePos 自动寻找。 */
	public static BlockPos findAppliedNode(Level level, BlockPos signalPos) {
		final NodeBinding nodeBinding = ClientBindings.getNodeBinding(signalPos);
		if (nodeBinding != null && nodeBinding.node() != null) {
			return nodeBinding.node();
		}
		final BlockState state = level.getBlockState(signalPos);
		if (!isSignalBlock(state)) {
			return null;
		}
		final float angle = getSignalAngle(state);
		final Direction facing = Direction.fromYRot(angle);
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
							closestPos = checkPos;
						}
					}
				}
			}
		}
		return closestPos;
	}

	/** 该信号机某一面（正面/背面）当前应有的显示状态。 */
	public static AspectData getAspectData(Level level, BlockPos signalPos, float signalAngle, boolean isBackSide) {
		final BlockPos startPos = findAppliedNode(level, signalPos);
		if (startPos == null) {
			return null;
		}

		final float angle = signalAngle + (isBackSide ? 180 : 0) + 90;
		final MinecraftClientData clientData = MinecraftClientData.getInstance();
		final Position startPosition = new Position(startPos.getX(), startPos.getY(), startPos.getZ());
		final List<Integer> detectedColors = new ArrayList<>();
		final List<Integer> occupiedColors = new ArrayList<>();
		final boolean[] blocked = {false};
		final List<String> railIds = new ArrayList<>();

		clientData.positionsToRail.getOrDefault(startPosition, new Object2ObjectOpenHashMap<>()).forEach((endPosition, rail) -> {
			final double railAngle = Math.toDegrees(Math.atan2(endPosition.getZ() - startPos.getZ(), endPosition.getX() - startPos.getX()));
			if (Math.abs(circularDifference(railAngle, angle)) < 90) {
				rail.getSignalColors().forEach(color -> detectedColors.add((int) color));
				final String railId = rail.getHexId();
				final LongArrayList blockedColors = clientData.railIdToCurrentlyBlockedSignalColors.getOrDefault(railId, new LongArrayList());
				blockedColors.forEach(color -> occupiedColors.add((int) color));
				if (clientData.blockedRailIds.contains(TwoPositionsBase.getHexIdRaw(startPosition, endPosition))) {
					blocked[0] = true;
				}
				railIds.add(railId);
			}
		});

		detectedColors.sort(Integer::compareTo);
		return new AspectData(detectedColors, occupiedColors, blocked[0], railIds);
	}

	/** 本信号前方区段是否被占用（含信号颜色过滤）。 */
	public static boolean getOccupied(Level level, BlockPos signalPos, BlockEntity blockEntity, boolean isBackSide) {
		if (!(blockEntity instanceof BlockSignalBase.BlockEntityBase entity)) {
			return false;
		}
		// BR 闭塞：列车一旦进入本信号到下一信号之间的区段，立即判红（不依赖 MTR 阻塞数据的同步延迟）
		if (isSectionOccupied(level, signalPos, isBackSide)) {
			return true;
		}
		final AspectData aspectData = getAspectData(level, signalPos, getSignalAngle(level.getBlockState(signalPos)), isBackSide);
		if (aspectData == null) {
			return false;
		}
		final IntAVLTreeSet filterColors = entity.getSignalColors(isBackSide);
		return filterColors.isEmpty() && aspectData.nodeBlocked
				|| aspectData.occupiedColors.stream().anyMatch(color -> filterColors.isEmpty() || filterColors.contains(color));
	}

	/**
	 * 判断是否有列车占用本信号防护的闭塞区段（本信号节点 -> 下一信号节点）。
	 * 使用客户端实时模拟的列车头/尾里程，列车头进入区段即判占用，列车尾离开区段才释放。
	 */
	private static boolean isSectionOccupied(Level level, BlockPos signalPos, boolean isBackSide) {
		final BlockPos thisNode = findAppliedNode(level, signalPos);
		if (thisNode == null) {
			return false;
		}
		SignalCache.tick(level);
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final List<PathData> path = vehicle.vehicleExtraData.immutablePath;
			if (path.isEmpty()) {
				continue;
			}
			final int thisIndex = indexOfNode(path, thisNode);
			if (thisIndex < 0) {
				continue;
			}
			final double sectionStart = distanceAtNode(path.get(thisIndex), thisNode);
			final double sectionEnd = nextSignalDistanceOnPath(level, path, thisIndex, signalPos);
			final double head = getRailProgress(vehicle);
			if (head < 0) {
				continue;
			}
			final double tail = head - vehicle.vehicleExtraData.getTotalVehicleLength();
			if (head > sectionStart && tail < sectionEnd) {
				return true;
			}
		}
		return false;
	}

	/** 节点在进路上的累计里程：匹配段起点则用段起点距离，匹配段终点则用段终点距离。 */
	private static double distanceAtNode(PathData segment, BlockPos nodePos) {
		if (matches(nodePos, segment.getOrderedPosition1())) {
			return segment.getStartDistance();
		}
		return segment.getEndDistance();
	}

	/** 沿该列车进路，找到本信号之后下一信号节点的累计里程；找不到则返回进路末端里程。 */
	private static double nextSignalDistanceOnPath(Level level, List<PathData> path, int fromIndex, BlockPos signalPos) {
		for (int i = fromIndex + 1; i < path.size(); i++) {
			final PathData pathData = path.get(i);
			final BlockPos nextSignal = SignalCache.getSignalForNode(toBlockPos(pathData.getOrderedPosition1()));
			if (nextSignal != null && !nextSignal.equals(signalPos)) {
				return pathData.getStartDistance();
			}
			final BlockPos nextSignal2 = SignalCache.getSignalForNode(toBlockPos(pathData.getOrderedPosition2()));
			if (nextSignal2 != null && !nextSignal2.equals(signalPos)) {
				return pathData.getEndDistance();
			}
		}
		return path.get(path.size() - 1).getEndDistance();
	}

	/** BR 闭塞链 aspect：0 绿 / 1 红 / 2 单黄 / 3 双黄（带每 tick 缓存）。 */
	public static int getSignalAspect(Level level, BlockPos signalPos, BlockEntity blockEntity, boolean isBackSide) {
		if (!(blockEntity instanceof BlockSignalBase.BlockEntityBase)) {
			return 0;
		}
		final long gameTime = level.getGameTime();
		if (gameTime != lastAspectCacheTime) {
			lastAspectCacheTime = gameTime;
			ASPECT_CACHE.clear();
		}
		final long cacheKey = (signalPos.asLong() << 1) | (isBackSide ? 1 : 0);
		final Integer cached = ASPECT_CACHE.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		final int aspect = resolveAspect(level, signalPos, isBackSide, 0, new HashSet<>());
		ASPECT_CACHE.put(cacheKey, aspect);
		return aspect;
	}

	private static int resolveAspect(Level level, BlockPos signalPos, boolean isBackSide, int depth, Set<BlockPos> visited) {
		if (depth >= MAX_CHAIN_DEPTH || !visited.add(signalPos)) {
			return 0;
		}
		if (getOccupied(level, signalPos, level.getBlockEntity(signalPos), isBackSide)) {
			visited.remove(signalPos);
			return 1;
		}
		final List<BlockPos> nextSignals = findNextSignalsOnRoutes(level, signalPos);
		if (nextSignals.isEmpty()) {
			visited.remove(signalPos);
			// 有列车进路经过但后面没有信号机（进路末端）→ 绿；否则未开放进路 → 红
			return isSignalOnAnyRoute(level, signalPos) ? 0 : 1;
		}
		int mostRestrictive = 0;
		for (final BlockPos nextSignal : nextSignals) {
			final int nextAspect = resolveAspect(level, nextSignal, false, depth + 1, visited);
			if (restrictiveness(nextAspect) > restrictiveness(mostRestrictive)) {
				mostRestrictive = nextAspect;
			}
		}
		visited.remove(signalPos);
		if (mostRestrictive == 1) {
			return 2;
		}
		if (mostRestrictive == 2) {
			return 3;
		}
		return 0;
	}

	/** 该信号机是否出现在任意列车进路上（用于区分“进路末端绿灯”与“未开放进路红灯”）。 */
	private static boolean isSignalOnAnyRoute(Level level, BlockPos signalPos) {
		final BlockPos thisNode = findAppliedNode(level, signalPos);
		if (thisNode == null) {
			return false;
		}
		SignalCache.tick(level);
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (indexOfNode(vehicle.vehicleExtraData.immutablePath, thisNode) >= 0) {
				return true;
			}
		}
		return false;
	}

	private static int restrictiveness(int aspect) {
		return switch (aspect) {
			case 1 -> 3;
			case 2 -> 2;
			case 3 -> 1;
			default -> 0;
		};
	}

	/** 沿所有列车进路，找出本信号之后的下一组信号机。 */
	private static List<BlockPos> findNextSignalsOnRoutes(Level level, BlockPos signalPos) {
		final List<BlockPos> nextSignals = new ArrayList<>();
		final BlockPos thisNode = findAppliedNode(level, signalPos);
		if (thisNode == null) {
			return nextSignals;
		}
		SignalCache.tick(level);
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final List<PathData> path = vehicle.vehicleExtraData.immutablePath;
			final int thisIndex = indexOfNode(path, thisNode);
			if (thisIndex < 0) {
				continue;
			}
			for (int i = thisIndex + 1; i < path.size(); i++) {
				final PathData pathData = path.get(i);
				final BlockPos nextSignal = SignalCache.getSignalForNode(toBlockPos(pathData.getOrderedPosition1()));
				if (nextSignal != null) {
					if (!nextSignal.equals(signalPos)) {
						nextSignals.add(nextSignal);
					}
					break;
				}
				final BlockPos nextSignal2 = SignalCache.getSignalForNode(toBlockPos(pathData.getOrderedPosition2()));
				if (nextSignal2 != null) {
					if (!nextSignal2.equals(signalPos)) {
						nextSignals.add(nextSignal2);
					}
					break;
				}
			}
		}
		return nextSignals;
	}

	private static int indexOfNode(List<PathData> path, BlockPos nodePos) {
		for (int i = 0; i < path.size(); i++) {
			final PathData pathData = path.get(i);
			if (matches(nodePos, pathData.getOrderedPosition1()) || matches(nodePos, pathData.getOrderedPosition2())) {
				return i;
			}
		}
		return -1;
	}

	private static BlockPos toBlockPos(Position position) {
		return new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
	}

	/**
	 * 该节点处、与信号机朝向最接近的轨道方向（单位向量 dx,dz）。
	 * 用于在轨道上绘制绑定方向箭头。
	 */
	public static double[] getTrackDirection(Level level, BlockPos signalPos, BlockPos nodePos) {
		final float signalAngle = getSignalAngle(level.getBlockState(signalPos));
		final MinecraftClientData clientData = MinecraftClientData.getInstance();
		final Position startPosition = new Position(nodePos.getX(), nodePos.getY(), nodePos.getZ());
		final double[] best = {Double.MAX_VALUE, 1, 0};
		final boolean[] found = {false};
		clientData.positionsToRail.getOrDefault(startPosition, new Object2ObjectOpenHashMap<>()).forEach((endPosition, rail) -> {
			final double railAngle = Math.toDegrees(Math.atan2(endPosition.getZ() - nodePos.getZ(), endPosition.getX() - nodePos.getX()));
			final double difference = Math.abs(circularDifference(railAngle, signalAngle));
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

	/**
	 * 该信号机当前“已开放”的进路绑定（供进路显示器/车上提示使用）：
	 * 绑定节点出现在某辆列车进路上、且位于本信号之后。
	 */
	public static List<RouteBinding> getOpenRouteBindings(Level level, BlockPos signalPos) {
		final List<RouteBinding> result = new ArrayList<>();
		final BlockPos thisNode = findAppliedNode(level, signalPos);
		final List<RouteBinding> allBindings = ClientBindings.get(signalPos);
		if (thisNode == null || allBindings.isEmpty()) {
			return result;
		}
		SignalCache.tick(level);
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final List<PathData> path = vehicle.vehicleExtraData.immutablePath;
			final int thisIndex = indexOfNode(path, thisNode);
			if (thisIndex < 0) {
				continue;
			}
			for (final RouteBinding binding : allBindings) {
				final int bindingIndex = indexOfNode(path, binding.node());
				if (bindingIndex > thisIndex && !result.contains(binding)) {
					result.add(binding);
				}
			}
		}
		return result;
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

	private static boolean matches(BlockPos nodePos, Position position) {
		return nodePos.getX() == position.getX() && nodePos.getY() == position.getY() && nodePos.getZ() == position.getZ();
	}

	public static final class AspectData {
		public final List<Integer> detectedColors;
		public final List<Integer> occupiedColors;
		public final boolean nodeBlocked;
		public final List<String> railIds;

		private AspectData(List<Integer> detectedColors, List<Integer> occupiedColors, boolean nodeBlocked, List<String> railIds) {
			this.detectedColors = detectedColors;
			this.occupiedColors = occupiedColors;
			this.nodeBlocked = nodeBlocked;
			this.railIds = railIds;
		}
	}
}
