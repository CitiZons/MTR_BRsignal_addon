package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.data.RouteBindingsSavedData;

import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mtrbr.client.ServerAspectCache;

/** Server-authoritative SignalFace aspect projection. */
public final class ServerAspectManager {
	private static final Map<String, SignalDisplay> ASPECTS = new HashMap<>();
	private static volatile Map<String, FaceSnapshot> FACE_SNAPSHOTS = Map.of();
	private static final Map<String, Long> REGISTRY_REVISIONS = new HashMap<>();
	private static final java.util.Set<String> TOPOLOGY_DIRTY = new java.util.HashSet<>();
	private static long lastAspectDebugMillis;
	private static long lastFacesDebugMillis;
	private static int lastFacesCount = -1;

	private ServerAspectManager() {
	}

	public static boolean update(ServerLevel level) {
		final String dimension = simulatorDimension(level);
		final Simulator simulator = SectionStateManager.getSimulator(dimension);
		if (simulator == null) {
			return false;
		}
		final Map<String, SignalDisplay> next = new HashMap<>();
		final FaceSnapshot existing = FACE_SNAPSHOTS.get(dimension);
		final long registryRevision = ServerSignalRegistry.getRevision(level);
		final boolean rebuildTopology;
		synchronized (ASPECTS) {
			rebuildTopology = existing == null || TOPOLOGY_DIRTY.remove(dimension) || REGISTRY_REVISIONS.getOrDefault(dimension, -1L) != registryRevision;
		}
		final Map<String, SignalFace> faces;
		if (rebuildTopology) {
			faces = SignalTopology.build(level);
			publishFaces(dimension, faces, registryRevision);
		} else {
			faces = existing.faces();
		}
		final RouteBindingsSavedData savedBindings = RouteBindingsSavedData.get(level);
		final List<RouteRequestManager.AuthorizedPath> authorizations = RouteRequestManager.getAuthorizedPaths(simulator);
		final List<RouteRequestManager.VehicleSnapshot> vehicles = RouteRequestManager.getVehicleSnapshots(simulator);
		if (faces.size() != lastFacesCount) {
			lastFacesCount = faces.size();
			final long now = System.currentTimeMillis();
			if (now - lastFacesDebugMillis >= 5000) {
				lastFacesDebugMillis = now;
				final StringBuilder faceDebug = new StringBuilder("[MTRBR-FACES] count=" + faces.size());
				for (final SignalFace face : faces.values()) {
					faceDebug.append(" | ")
							.append(face.signalPos().getX()).append(',').append(face.signalPos().getY()).append(',').append(face.signalPos().getZ())
							.append("->").append(face.nodePos().getX()).append(',').append(face.nodePos().getY()).append(',').append(face.nodePos().getZ())
							.append(" ang=").append(String.format("%.0f", face.travelAngle()));
				}
				System.out.println(faceDebug);
			}
		}
		final StringBuilder aspectDebug = new StringBuilder();
		for (final SignalFace face : faces.values()) {
			// 列车进入该信号防护的闭塞区段后立即变红，不等待整段 request 释放。
			if (isSectionOccupied(faces, face, vehicles)) {
				next.put(key(dimension, face.signalPos(), face.backSide()), new SignalDisplay(ServerAspect.RED, "", "", 0));
				continue;
			}
			ServerAspect aspect = ServerAspect.RED;
			String authorizationId = "";
			String routeContent = "";
			long revision = 0;
			for (final RouteRequestManager.AuthorizedPath authorization : authorizations) {
				if (covers(authorization, face)) {
					aspect = resolveAspect(faces, authorizations, authorization.path(), face, new HashSet<>());
					authorizationId = authorization.authorizationId();
					routeContent = authorizedRouteContent(savedBindings, authorization, face, authorization.path().getDistanceAtNode(face.nodePos()));
					revision = authorization.revision();
					if (aspect != ServerAspect.RED) {
						aspectDebug.append(" | ")
								.append(face.signalPos().getX()).append(',').append(face.signalPos().getY()).append(',').append(face.signalPos().getZ())
								.append(" side=").append(face.backSide() ? 'B' : 'F')
								.append(" faceAng=").append(String.format("%.0f", face.travelAngle()))
								.append(" pathAng=").append(String.format("%.0f", authorization.path().getTravelAngleAtNode(face.nodePos())))
								.append(" node=").append(face.nodePos().getX()).append(',').append(face.nodePos().getY()).append(',').append(face.nodePos().getZ())
								.append(" a=").append(aspect)
								.append(" auth=").append(authorizationId);
					}
					break;
				}
			}
			next.put(key(dimension, face.signalPos(), face.backSide()), new SignalDisplay(aspect, authorizationId, routeContent, revision));
		}
		if (aspectDebug.length() > 0) {
			final long now = System.currentTimeMillis();
			if (now - lastAspectDebugMillis >= 5000) {
				lastAspectDebugMillis = now;
				System.out.println("[MTRBR-ASPECT] " + dimension + aspectDebug);
			}
		}
		synchronized (ASPECTS) {
			final Map<String, SignalDisplay> oldAspects = new HashMap<>(ASPECTS);
			ASPECTS.keySet().removeIf(key -> key.startsWith(dimension + "|"));
			ASPECTS.putAll(next);
			return !oldAspects.equals(ASPECTS);
		}
	}

	public static Map<ServerAspectCache.Key, ServerAspectCache.DisplayState> snapshot(ServerLevel level) {
		final String dimension = simulatorDimension(level);
		final Map<ServerAspectCache.Key, ServerAspectCache.DisplayState> result = new HashMap<>();
		synchronized (ASPECTS) {
			for (final SignalFace face : getFaceSnapshot(dimension).faces().values()) {
				final SignalDisplay display = ASPECTS.get(key(dimension, face.signalPos(), face.backSide()));
				if (display != null) {
					result.put(new ServerAspectCache.Key(face.signalPos(), face.backSide()), new ServerAspectCache.DisplayState(display.aspect().getValue(), display.authorizationId(), display.routeContent(), display.revision()));
				}
			}
		}
		return result;
	}

	public static Map<String, SignalFace> getFaces(String simulatorDimension) {
		return getFaceSnapshot(simulatorDimension).faces();
	}

	public static FaceSnapshot getFaceSnapshot(String simulatorDimension) {
		return FACE_SNAPSHOTS.getOrDefault(simulatorDimension, new FaceSnapshot(Map.of(), 0));
	}

	public static void invalidateTopology(ServerLevel level) {
		synchronized (ASPECTS) {
			TOPOLOGY_DIRTY.add(simulatorDimension(level));
		}
	}

	public static ServerAspect get(ServerLevel level, BlockPos signalPos, boolean reversed) {
		synchronized (ASPECTS) {
			final SignalDisplay display = ASPECTS.get(key(simulatorDimension(level), signalPos, reversed));
			return display == null ? null : display.aspect();
		}
	}

	/** Clears topology/aspect caches when the server stops, so a new world session starts clean. */
	public static void resetAll() {
		synchronized (ASPECTS) {
			ASPECTS.clear();
			REGISTRY_REVISIONS.clear();
			TOPOLOGY_DIRTY.clear();
		}
		FACE_SNAPSHOTS = Map.of();
	}

	private static String key(String dimension, BlockPos signalPos, boolean reversed) {
		return dimension + "|" + signalPos.asLong() + "|" + reversed;
	}

	private record SignalDisplay(ServerAspect aspect, String authorizationId, String routeContent, long revision) {
	}

	/** Immutable topology published from the server thread to simulation threads. */
	public record FaceSnapshot(Map<String, SignalFace> faces, long revision) {
		public FaceSnapshot {
			faces = Map.copyOf(faces);
		}
	}

	private static void publishFaces(String dimension, Map<String, SignalFace> faces, long registryRevision) {
		final FaceSnapshot old = FACE_SNAPSHOTS.get(dimension);
		final Map<String, FaceSnapshot> next = new HashMap<>(FACE_SNAPSHOTS);
		final FaceSnapshot published = old != null && old.faces().equals(faces) ? old : new FaceSnapshot(faces, old == null ? 1 : old.revision() + 1);
		next.put(dimension, published);
		FACE_SNAPSHOTS = Collections.unmodifiableMap(next);
		synchronized (ASPECTS) {
			REGISTRY_REVISIONS.put(dimension, registryRevision);
		}
	}

	private static boolean covers(RouteRequestManager.AuthorizedPath authorization, SignalFace face) {
		final double distance = authorization.path().getDistanceAtNode(face.nodePos());
		return distance >= authorization.startDistance() && distance < authorization.endDistance() && travelsInFaceDirection(authorization.path(), face);
	}

	/** 该信号防护区段（本信号节点到下一同向信号节点）是否被任意列车占用。 */
	private static boolean isSectionOccupied(Map<String, SignalFace> faces, SignalFace face, List<RouteRequestManager.VehicleSnapshot> vehicles) {
		for (final RouteRequestManager.VehicleSnapshot vehicle : vehicles) {
			final double faceDistance = vehicle.path().getDistanceAtNode(face.nodePos());
			if (faceDistance < 0 || !travelsInFaceDirection(vehicle.path(), face)) {
				continue;
			}
			if (vehicle.head() <= faceDistance) {
				continue; // 车头尚未越过本信号
			}
			final double nextDistance = nextFaceDistance(faces, vehicle.path(), face, faceDistance);
			if (vehicle.tail() < nextDistance) {
				return true; // 车头已过本信号、车尾未到下一信号：区段占用
			}
		}
		return false;
	}

	private static double nextFaceDistance(Map<String, SignalFace> faces, PathSnapshot path, SignalFace face, double faceDistance) {
		double best = Double.MAX_VALUE;
		for (final SignalFace candidate : faces.values()) {
			if (candidate.id().equals(face.id()) || !travelsInFaceDirection(path, candidate)) {
				continue;
			}
			final double distance = path.getDistanceAtNode(candidate.nodePos());
			if (distance > faceDistance && distance < best) {
				best = distance;
			}
		}
		return best == Double.MAX_VALUE ? path.getTotalDistance() : best;
	}

	/**
	 * 沿授权 Path 递归解析灯序：未授权信号视为红灯；本信号 = 下一信号状态的
	 * 预告（下一红→单黄，下一黄→双黄，其余→绿）。授权向前延伸时灯序链随之
	 * 移动，列车通过已授权区段后信号仍保持正确预告。
	 */
	private static ServerAspect resolveAspect(Map<String, SignalFace> faces, List<RouteRequestManager.AuthorizedPath> authorizations, PathSnapshot path, SignalFace face, Set<String> visited) {
		if (!visited.add(face.id())) {
			return ServerAspect.RED;
		}
		final double faceDistance = path.getDistanceAtNode(face.nodePos());
		final SignalFace nextFace = faces.values().stream()
				.filter(candidate -> !candidate.id().equals(face.id()) && travelsInFaceDirection(path, candidate))
				.filter(candidate -> path.getDistanceAtNode(candidate.nodePos()) > faceDistance)
				.min(java.util.Comparator.comparingDouble(candidate -> path.getDistanceAtNode(candidate.nodePos())))
				.orElse(null);
		if (nextFace == null) {
			visited.remove(face.id());
			// 已授权进路到达明确 Terminal/Depot/折返点：最后一架信号为绿。
			return ServerAspect.GREEN;
		}
		final boolean nextCovered = authorizations.stream().anyMatch(authorization -> covers(authorization, nextFace));
		if (!nextCovered) {
			visited.remove(face.id());
			return ServerAspect.YELLOW; // 下一信号红 → 单黄
		}
		final ServerAspect nextAspect = resolveAspect(faces, authorizations, path, nextFace, visited);
		visited.remove(face.id());
		if (nextAspect == ServerAspect.YELLOW) {
			return ServerAspect.DOUBLE_YELLOW;
		}
		return ServerAspect.GREEN;
	}

	/** First route binding whose node lies on the authorized path after this face. */
	private static String authorizedRouteContent(RouteBindingsSavedData saved, RouteRequestManager.AuthorizedPath authorization, SignalFace face, double faceDistance) {
		String best = "";
		double bestDistance = Double.MAX_VALUE;
		for (final RouteBinding binding : saved.getBindings(face.signalPos())) {
			if (binding.node() == null) {
				continue;
			}
			final double nodeDistance = authorization.path().getDistanceAtNode(binding.node());
			if (nodeDistance > faceDistance && nodeDistance <= authorization.endDistance() && nodeDistance < bestDistance) {
				best = binding.content();
				bestDistance = nodeDistance;
			}
		}
		return best;
	}

	private static boolean travelsInFaceDirection(PathSnapshot path, SignalFace face) {
		final double pathAngle = path.getTravelAngleAtNode(face.nodePos());
		return !Double.isNaN(pathAngle) && circularDifference(pathAngle, face.travelAngle()) < 90;
	}

	private static double circularDifference(double first, double second) {
		double difference = (first - second) % 360;
		if (difference < -180) {
			difference += 360;
		}
		if (difference > 180) {
			difference -= 360;
		}
		return Math.abs(difference);
	}

	private static String simulatorDimension(ServerLevel level) {
		return level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
	}
}
