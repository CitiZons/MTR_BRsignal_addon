package org.mtrbr.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtrbr.client.SignalCache;
import org.mtrbr.data.ClientBindings;
import org.mtrbr.data.RouteBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 沿列车进路向前读取，找到下一信号机并返回其进路绑定内容。
 * 读取范围：列车当前位置 -> 进路终点（后续可限制到下一停站）。
 */
public final class VehicleRouteReader {

	private VehicleRouteReader() {
	}

	public static List<String> getNextSignalContents() {
		final Minecraft minecraft = Minecraft.getInstance();
		final Player player = minecraft.player;
		final Level level = minecraft.level;
		if (player == null || level == null) {
			return List.of();
		}

		final VehicleExtension vehicle = findRidingVehicle(player);
		if (vehicle == null) {
			return List.of("未检测到 MTR 列车");
		}

		final List<PathData> path = vehicle.vehicleExtraData.immutablePath;
		if (path.isEmpty()) {
			return List.of("列车进路为空");
		}

		final int currentIndex = findCurrentSegment(path, player.position());
		SignalCache.tick(level, player.blockPosition());
		final List<SignalCache.Entry> entries = SignalCache.getEntries(level, player.blockPosition());

		for (int i = Math.max(0, currentIndex); i < path.size(); i++) {
			final PathData pathData = path.get(i);
			final Position position1 = pathData.getOrderedPosition1();
			final Position position2 = pathData.getOrderedPosition2();
			for (final SignalCache.Entry entry : entries) {
				final BlockPos nodePos = entry.nodePos();
				if (nodePos == null) {
					continue;
				}
				final boolean isCurrentSegment = i == Math.max(0, currentIndex);
				// 当前所在段的起点节点已经驶过，只检查段终点
				final boolean matched = isCurrentSegment ? matches(nodePos, position2) : matches(nodePos, position1) || matches(nodePos, position2);
				if (matched) {
					final List<RouteBinding> applicable = new ArrayList<>();
					for (final RouteBinding binding : ClientBindings.get(entry.signalPos())) {
						if (isNodeOnPathAhead(path, i, nodePos, position1, position2, binding.node())) {
							applicable.add(binding);
						}
					}
					if (applicable.isEmpty()) {
						return List.of("下一信号机: (无匹配进路)");
					}
					final List<String> contents = new ArrayList<>();
					for (final RouteBinding binding : applicable) {
						contents.add("下一信号机: " + binding.content());
					}
					return contents;
				}
			}
		}
		return List.of("前方进路无绑定信号机");
	}

	/** 绑定节点是否出现在该列车进路的后续区段中（本信号之后）。 */
	private static boolean isNodeOnPathAhead(List<PathData> path, int fromIndex, BlockPos signalNode, Position segmentPosition1, Position segmentPosition2, BlockPos bindingNode) {
		final boolean signalAtSegmentEnd = matches(signalNode, segmentPosition2);
		for (int i = fromIndex; i < path.size(); i++) {
			final PathData pathData = path.get(i);
			if (i > fromIndex) {
				if (matches(bindingNode, pathData.getOrderedPosition1()) || matches(bindingNode, pathData.getOrderedPosition2())) {
					return true;
				}
			} else if (!signalAtSegmentEnd && matches(bindingNode, pathData.getOrderedPosition2())) {
				return true;
			}
		}
		return false;
	}

	private static VehicleExtension findRidingVehicle(Player player) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final boolean[] found = {false};
			vehicle.vehicleExtraData.iterateRidingEntities(ridingEntity -> {
				if (ridingEntity.uuid.equals(player.getUUID())) {
					found[0] = true;
				}
			});
			if (found[0]) {
				return vehicle;
			}
		}
		return null;
	}

	private static int findCurrentSegment(List<PathData> path, Vec3 playerPosition) {
		int bestIndex = 0;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			final PathData pathData = path.get(i);
			final Vec3 start = toVec3(pathData.getOrderedPosition1());
			final Vec3 end = toVec3(pathData.getOrderedPosition2());
			final double distance = distanceToSegment(playerPosition, start, end);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestIndex = i;
			}
		}
		return bestIndex;
	}

	private static Vec3 toVec3(Position position) {
		return new Vec3(position.getX(), position.getY(), position.getZ());
	}

	private static double distanceToSegment(Vec3 point, Vec3 start, Vec3 end) {
		final Vec3 segment = end.subtract(start);
		final double lengthSquared = segment.lengthSqr();
		if (lengthSquared < 1.0E-6) {
			return point.distanceTo(start);
		}
		final double t = Math.max(0, Math.min(1, point.subtract(start).dot(segment) / lengthSquared));
		return point.distanceTo(start.add(segment.scale(t)));
	}

	private static boolean matches(BlockPos nodePos, Position position) {
		return nodePos.getX() == position.getX() && nodePos.getY() == position.getY() && nodePos.getZ() == position.getZ();
	}
}
