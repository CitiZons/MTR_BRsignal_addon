package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.data.RouteBindingsSavedData;
import org.mtrbr.data.SignalBlockSavedData;

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
	private static long lastSnapshotDebugMillis;
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
		final FaceSnapshot topology;
		final Map<String, SignalFace> faces;
		if (rebuildTopology) {
			final Map<String, SignalFace> rebuiltFaces = SignalTopology.build(level);
			// A transient registry/chunk refresh can return an empty topology. Do
			// not replace a known-good server topology with that empty result;
			// otherwise every vehicle loses its control faces for one tick window.
			faces = rebuiltFaces.isEmpty() && existing != null && !existing.faces().isEmpty() ? existing.faces() : rebuiltFaces;
			if (faces != rebuiltFaces) {
				// Keep the published revision and persisted topology unchanged.
				synchronized (ASPECTS) {
					REGISTRY_REVISIONS.put(dimension, registryRevision);
				}
			} else {
				publishFaces(dimension, faces, registryRevision);
			}
			topology = getFaceSnapshot(dimension);
		} else {
			faces = existing.faces();
			topology = existing;
		}
		final RouteBindingsSavedData savedBindings = RouteBindingsSavedData.get(level);
		final List<RouteRequestManager.AuthorizedPath> authorizations = RouteRequestManager.getAuthorizedPaths(simulator);
		final Map<String, SectionStateManager.SectionSnapshot> sectionStates = SectionStateManager.getPublishedSections(simulator);
		final SignalBlockSavedData signalBlocks = SignalBlockSavedData.get(level);
		final long nowSnapshot = System.currentTimeMillis();
		if (nowSnapshot - lastSnapshotDebugMillis >= 5000) {
			lastSnapshotDebugMillis = nowSnapshot;
			System.out.println("[MTRBR-ASPECT-SNAPSHOT] dim=" + dimension + " faces=" + faces.size()
					+ " authorizations=" + authorizations.size() + " sections=" + sectionStates.size());
		}
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
		final Map<String, String> diagnostics = new HashMap<>();
		for (final SignalFace face : faces.values()) {
			// Resolve the physical traversal identity first. Occupancy must know which
			// authorized vehicle owns this face; otherwise the train's own occupied
			// section makes its already-authorized signal permanently red.
			RouteRequestManager.AuthorizedPath coveringAuthorization = null;
			PathSnapshot.FaceTraversal coveredTraversal = null;
			for (final RouteRequestManager.AuthorizedPath authorization : authorizations) {
				final PathSnapshot.FaceTraversal candidate = coveredFaceTraversal(dimension, topology, authorization, face);
				if (candidate != null) {
					coveringAuthorization = authorization;
					coveredTraversal = candidate;
					break;
				}
			}
			final long owningVehicleId = coveringAuthorization == null ? Long.MIN_VALUE : coveringAuthorization.vehicleId();
			final String faceKey = key(dimension, face.signalPos(), face.backSide());
			final boolean occupancyConflict = isSectionOccupied(simulator, signalBlocks, face, sectionStates, owningVehicleId, coveringAuthorization == null ? "" : coveringAuthorization.authorizationId().replace(":auth", ""));
			if (occupancyConflict) {
				next.put(faceKey, new SignalDisplay(ServerAspect.RED, "", "", 0));
				diagnostics.put(faceKey, signalDiagnostic(signalBlocks, face, false, true, ServerAspect.RED));
				continue;
			}
			ServerAspect aspect = ServerAspect.RED;
			String authorizationId = "";
			String routeContent = "";
			long revision = 0;
			if (coveringAuthorization != null && coveredTraversal != null) {
				aspect = resolveAspect(dimension, topology, coveringAuthorization, coveredTraversal, new HashSet<>());
				// Keep the requestId internal for lock/occupancy checks; expose only
				// the stable dispatcher vehicle code to the client panel.
				authorizationId = coveringAuthorization.vehicleCode();
				routeContent = authorizedRouteContent(savedBindings, coveringAuthorization, face, coveredTraversal.distance());
				revision = coveringAuthorization.revision();
					if (aspect != ServerAspect.RED) {
						aspectDebug.append(" | ")
								.append(face.signalPos().getX()).append(',').append(face.signalPos().getY()).append(',').append(face.signalPos().getZ())
								.append(" side=").append(face.backSide() ? 'B' : 'F')
								.append(" faceAng=").append(String.format("%.0f", face.travelAngle()))
								.append(" pathAng=").append(String.format("%.0f", coveredTraversal.travelAngle()))
								.append(" node=").append(face.nodePos().getX()).append(',').append(face.nodePos().getY()).append(',').append(face.nodePos().getZ())
								.append(" a=").append(aspect)
								.append(" auth=").append(authorizationId);
					}
			}
			next.put(faceKey, new SignalDisplay(aspect, authorizationId, routeContent, revision));
			diagnostics.put(faceKey, signalDiagnostic(signalBlocks, face, coveredTraversal != null, false, aspect));
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
			for (final Map.Entry<String, SignalDisplay> entry : next.entrySet()) {
				final SignalDisplay previous = oldAspects.get(entry.getKey());
				if (!java.util.Objects.equals(previous, entry.getValue())) {
					final SignalDisplay current = entry.getValue();
					MtrbrDebugLog.event("SIGNAL", "key=" + entry.getKey()
							+ " aspect=" + (previous == null ? "<none>" : previous.aspect()) + "->" + current.aspect()
							+ " authorization=" + current.authorizationId()
							+ " routeIndicator=" + current.routeContent()
							+ " revision=" + current.revision());
					System.out.println("[MTRBR-SIGNAL] " + diagnostics.getOrDefault(entry.getKey(), "key=" + entry.getKey()) + " aspect=" + current.aspect());
				}
			}
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
					result.put(new ServerAspectCache.Key(face.signalPos(), face.backSide()), new ServerAspectCache.DisplayState(display.aspect().getValue(), display.authorizationId(), display.routeContent(), display.revision(), face.nodePos()));
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


	private static PathSnapshot.FaceTraversal coveredFaceTraversal(String dimension, FaceSnapshot topology, RouteRequestManager.AuthorizedPath authorization, SignalFace face) {
		return authorization.path().getFaceTraversals(dimension, topology).stream()
				.filter(point -> point.faceId().equals(face.id()))
				.filter(PathSnapshot::isDirectionMatched)
				.filter(point -> point.distance() >= authorization.startDistance() && point.distance() < authorization.endDistance())
				.filter(point -> authorization.activeFaceTraversalKeys().contains(point.key()))
				.findFirst().orElse(null);
	}

	private static boolean covers(String dimension, FaceSnapshot topology, RouteRequestManager.AuthorizedPath authorization, SignalFace face) {
		return coveredFaceTraversal(dimension, topology, authorization, face) != null;
	}

	/**
	 * 该信号防护区段（本信号节点到下一同向信号节点）是否被占用。
	 * VehicleSnapshot 的 path 只用于迁移期间推导 SignalFace -> Section ID；占用事实一律
	 * 读取 SectionStateManager 的服务端权威 snapshot，不再用头/尾里程自行重算。
	 */
	private static boolean isSectionOccupied(Simulator simulator, SignalBlockSavedData signalBlocks, SignalFace face, Map<String, SectionStateManager.SectionSnapshot> sectionStates, long owningVehicleId, String ownerId) {
		final String blockId = signalBlocks.getBlockId(face.id());
		if (blockId.isEmpty()) return true;
		if (SectionStateManager.isBlockConflicted(simulator, blockId, ownerId)) return true;
		final java.util.Set<String> protectedSectionIds = new java.util.HashSet<>(signalBlocks.getRailIdsForBlock(blockId));
		if (protectedSectionIds.isEmpty()) {
			// Default/fail-closed state remains unchanged when neither explicit nor
			// dynamically derived protection is available.
			return true;
		}
		for (final String sectionId : protectedSectionIds) {
			final SectionStateManager.SectionSnapshot state = sectionStates.get(sectionId);
			if (state != null && state.occupiedBy.stream().anyMatch(vehicleId -> vehicleId != owningVehicleId)) {
				// Occupancy is authoritative. The owning authorized vehicle is allowed
				// to occupy its own protected section; every other vehicle remains a
				// blocking conflict.
				return true;
			}
		}
		return false;
	}

	private static String signalDiagnostic(SignalBlockSavedData blocks, SignalFace face, boolean coveredByActivity, boolean occupancyConflict, ServerAspect aspect) {
		final String blockId = blocks.getBlockId(face.id());
		return "faceId=" + face.id() + " node=" + face.nodePos() + " blockId=" + (blockId.isEmpty() ? "<missing>" : blockId)
				+ " rails=" + blocks.getRailIdsForBlock(blockId) + " coveredByActivity=" + coveredByActivity
				+ " occupancyConflict=" + occupancyConflict + " computed=" + aspect;
	}

	/**
	 * 沿授权 Path 递归解析灯序：未授权信号视为红灯；本信号 = 下一信号状态的
	 * 预告（下一红→单黄，下一黄→双黄，其余→绿）。授权向前延伸时灯序链随之
	 * 移动，列车通过已授权区段后信号仍保持正确预告。
	 */
	private static ServerAspect resolveAspect(String dimension, FaceSnapshot topology, RouteRequestManager.AuthorizedPath authorization, PathSnapshot.FaceTraversal faceTraversal, Set<String> visited) {
		final String visitKey = faceTraversal.key().toString();
		if (!visited.add(visitKey)) {
			return ServerAspect.RED;
		}
		final List<PathSnapshot.FaceTraversal> faces = authorization.path().getFaceTraversals(dimension, topology).stream()
				.filter(PathSnapshot::isDirectionMatched).toList();
		final int index = faces.indexOf(faceTraversal);
		final PathSnapshot.FaceTraversal nextFace = index < 0 || index + 1 >= faces.size() ? null : faces.get(index + 1);
		if (nextFace == null) {
			visited.remove(visitKey);
			// 已授权进路到达明确 Terminal/Depot/折返点：最后一架信号为绿。
			return ServerAspect.GREEN;
		}
		final boolean nextCovered = authorization.activeFaceTraversalKeys().contains(nextFace.key());
		if (!nextCovered) {
			visited.remove(visitKey);
			return ServerAspect.YELLOW; // 下一信号红 → 单黄
		}
		final ServerAspect nextAspect = resolveAspect(dimension, topology, authorization, nextFace, visited);
		visited.remove(visitKey);
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
			for (final PathSnapshot.NodeDistance nodeDistance : authorization.path().getNodeDistances(binding.node())) {
				if (nodeDistance.distance() > faceDistance && nodeDistance.distance() <= authorization.endDistance() && nodeDistance.distance() < bestDistance) {
					best = binding.content();
					bestDistance = nodeDistance.distance();
				}
			}
		}
		return best;
	}

	private static double travelAngleAt(PathSnapshot path, double distance, BlockPos node) {
		return path.getNodeDistances(node).stream()
				.filter(point -> Double.compare(point.distance(), distance) == 0)
				.mapToDouble(PathSnapshot.NodeDistance::travelAngle).findFirst().orElse(Double.NaN);
	}

	private static String simulatorDimension(ServerLevel level) {
		return level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
	}

	private static double normalizeAngle(double angle) {
		double normalized = angle % 360;
		return normalized < 0 ? normalized + 360 : normalized;
	}
}
