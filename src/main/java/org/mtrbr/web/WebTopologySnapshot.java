package org.mtrbr.web;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtrbr.server.SectionStateManager;
import org.mtrbr.server.RouteRequestManager;
import org.mtrbr.server.ServerAspect;
import org.mtrbr.server.ServerAspectManager;
import org.mtrbr.server.MtrbrDebugLog;
import org.mtrbr.server.SignalFace;
import org.mtrbr.block.RepeatingSignalBlockEntity;
import org.mtrbr.data.RouteBindingsSavedData;
import org.mtrbr.network.Network;
import org.mtrbr.network.SyncRouteBindingsPacket;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable JSON documents published by the server thread for the MTR webserver. */
public final class WebTopologySnapshot {
	private static final double PLATFORM_MIN_LATERAL_DISTANCE = 2;
	private static final double PLATFORM_MAX_LATERAL_DISTANCE = 5;
	private static final double PLATFORM_INTERVAL_MERGE_GAP = 2.5;
	private static volatile String topologyJson = "{\"dimensions\":[]}";
	private static volatile String stateJson = "{\"dimensions\":[]}";
	private static volatile String linesJson = "{\"schema\":1,\"dimensions\":[]}";
	private static volatile MinecraftServer server;
	private static final Map<String, TopologyCacheEntry> TOPOLOGY_CACHE = new HashMap<>();
	private static final Map<String, LineCacheEntry> LINE_CACHE = new HashMap<>();
	private static final Map<Long, QuarantineState> QUARANTINE_STATES = new HashMap<>();

	private enum QuarantineState { QUARANTINED, DELETE_PENDING }

	private WebTopologySnapshot() {
	}

	public static void publish(MinecraftServer server) {
		WebTopologySnapshot.server = server;
		final JsonArray topologyDimensions = new JsonArray();
		final JsonArray stateDimensions = new JsonArray();
		final JsonArray lineDimensions = new JsonArray();
		final Set<String> activeDimensions = new HashSet<>();
		for (final ServerLevel level : server.getAllLevels()) {
			final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
			final Simulator simulator = SectionStateManager.getSimulator(dimension);
			if (simulator == null) continue;
			activeDimensions.add(dimension);
			final long topologyRevision = SectionStateManager.getTopologyRevision(simulator);
			final long platformRevision = PlatformGeometryCache.getRevision(level);
			final TopologyCacheEntry existing = TOPOLOGY_CACHE.get(dimension);
			final TopologyCacheEntry topology = existing != null && existing.matches(simulator, topologyRevision, platformRevision)
					? existing
					: new TopologyCacheEntry(simulator, topologyRevision, platformRevision, buildTopology(level, dimension, simulator));
			TOPOLOGY_CACHE.put(dimension, topology);
			topologyDimensions.add(topology.json());
			stateDimensions.add(buildState(dimension, simulator));
			final String lineFingerprint = lineFingerprint(simulator);
			final LineCacheEntry existingLines = LINE_CACHE.get(dimension);
			final LineCacheEntry lines = existingLines != null && existingLines.matches(simulator, topologyRevision, lineFingerprint)
					? existingLines
					: new LineCacheEntry(simulator, topologyRevision, lineFingerprint, buildLines(dimension, simulator));
			LINE_CACHE.put(dimension, lines);
			lineDimensions.add(lines.json());
		}
		TOPOLOGY_CACHE.keySet().removeIf(dimension -> !activeDimensions.contains(dimension));
		LINE_CACHE.keySet().removeIf(dimension -> !activeDimensions.contains(dimension));
		final JsonObject topology = new JsonObject();
		topology.addProperty("schema", 1);
		topology.add("dimensions", topologyDimensions);
		final JsonObject state = new JsonObject();
		state.addProperty("schema", 1);
		state.add("dimensions", stateDimensions);
		final JsonArray players = new JsonArray();
		for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("name", player.getGameProfile().getName());
			entry.addProperty("id", player.getUUID().toString());
			entry.addProperty("avatar", "https://mc-heads.net/avatar/" + player.getUUID() + "/16");
			entry.addProperty("dispatching", player.hasPermissions(2) && WebSessionManager.isDispatching(player.getUUID()));
			players.add(entry);
		}
		state.add("players", players);
		final JsonObject lines = new JsonObject();
		lines.addProperty("schema", 1);
		lines.add("dimensions", lineDimensions);
		topologyJson = topology.toString();
		stateJson = state.toString();
		linesJson = lines.toString();
	}

	/** Forces the next publish to rebuild block-entity-derived topology links. */
	public static void invalidateTopology(ServerLevel level) {
		if (level != null) TOPOLOGY_CACHE.remove(level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath());
	}

	public static String topologyJson() {
		return topologyJson;
	}

	public static String stateJson() {
		return stateJson;
	}

	/** Static Depot path topology. This deliberately excludes vehicles and Section state. */
	public static String linesJson() {
		return linesJson;
	}

	public static MinecraftServer server() {
		return server;
	}

	public static void reset() {
		topologyJson = "{\"dimensions\":[]}";
		stateJson = "{\"dimensions\":[]}";
		linesJson = "{\"schema\":1,\"dimensions\":[]}";
		server = null;
		TOPOLOGY_CACHE.clear();
		LINE_CACHE.clear();
		synchronized (QUARANTINE_STATES) { QUARANTINE_STATES.clear(); }
	}

	public static WebSessionManager.SessionView session(String token, String deviceId) {
		final MinecraftServer current = server;
		return current == null ? new WebSessionManager.SessionView(false, "") : WebSessionManager.access(current, token, deviceId);
	}

	public static boolean dispatch(String token, String deviceId, String action, long vehicleId) {
		final MinecraftServer current = server;
		if (current == null || !WebSessionManager.access(current, token, deviceId).canDispatch()) {
			MtrbrDebugLog.event("WEB-DISPATCH", "actor=<forbidden> action=" + action + " vehicle=" + vehicleId + " accepted=false");
			return false;
		}
		final String actor = WebSessionManager.operator(current, token);
		if ("quarantine".equals(action) || "release".equals(action) || "delete_pending".equals(action) || "cancel_delete".equals(action)) {
			synchronized (QUARANTINE_STATES) {
				final QuarantineState state = QUARANTINE_STATES.get(vehicleId);
				final boolean exists = true;
				final boolean accepted;
				switch (action) {
					case "quarantine" -> { accepted = exists && state == null; if (accepted) QUARANTINE_STATES.put(vehicleId, QuarantineState.QUARANTINED); }
					case "release" -> { accepted = state == QuarantineState.QUARANTINED; if (accepted) QUARANTINE_STATES.remove(vehicleId); }
					case "delete_pending" -> { accepted = state == QuarantineState.QUARANTINED; if (accepted) QUARANTINE_STATES.put(vehicleId, QuarantineState.DELETE_PENDING); }
					case "cancel_delete" -> { accepted = state == QuarantineState.DELETE_PENDING; if (accepted) QUARANTINE_STATES.put(vehicleId, QuarantineState.QUARANTINED); }
					default -> { accepted = false; }
				}
				MtrbrDebugLog.event("WEB-DISPATCH", "actor=" + actor + " action=" + action + " vehicle=" + vehicleId + " accepted=" + accepted);
				return accepted;
			}
		}
		// A quarantined vehicle may no longer have a live request snapshot. Resolve
		// explicit removal against every simulator before applying the request gate
		// used by approve/revoke/override.
		if ("confirm_quarantine_removal".equals(action)) {
			for (final ServerLevel level : current.getAllLevels()) {
				final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
				final Simulator simulator = SectionStateManager.getSimulator(dimension);
				if (simulator != null && QUARANTINE_STATES.getOrDefault(vehicleId, QuarantineState.DELETE_PENDING) == QuarantineState.DELETE_PENDING && RouteRequestManager.forceRemoveVehicle(simulator, vehicleId)) {
					synchronized (QUARANTINE_STATES) { QUARANTINE_STATES.remove(vehicleId); }
					MtrbrDebugLog.event("WEB-DISPATCH", "actor=" + actor + " action=" + action + " vehicle=" + vehicleId + " dimension=" + dimension + " accepted=true");
					return true;
				}
			}
			MtrbrDebugLog.event("WEB-DISPATCH", "actor=" + actor + " action=" + action + " vehicle=" + vehicleId + " accepted=false reason=NOT_QUARANTINED");
			return false;
		}
		for (final ServerLevel level : current.getAllLevels()) {
			final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
			final Simulator simulator = SectionStateManager.getSimulator(dimension);
			if (simulator == null || RouteRequestManager.getRequestSnapshots(simulator).stream().noneMatch(entry -> entry.vehicleId() == vehicleId)) continue;
			switch (action) {
				case "approve" -> RouteRequestManager.approveWaiting(simulator, vehicleId);
				case "revoke" -> RouteRequestManager.revokePendingAuthorization(simulator, vehicleId);
				case "override" -> RouteRequestManager.grantOneShotOverride(simulator, vehicleId);
				default -> {
					MtrbrDebugLog.event("WEB-DISPATCH", "actor=" + actor + " action=" + action + " vehicle=" + vehicleId + " accepted=false reason=UNKNOWN_ACTION");
					return false;
				}
			}
			MtrbrDebugLog.event("WEB-DISPATCH", "actor=" + actor + " action=" + action + " vehicle=" + vehicleId + " dimension=" + dimension + " accepted=true");
			return true;
		}
		MtrbrDebugLog.event("WEB-DISPATCH", "actor=" + actor + " action=" + action + " vehicle=" + vehicleId + " accepted=false reason=VEHICLE_NOT_FOUND");
		return false;
	}

	public static String quarantineState(long vehicleId) {
		synchronized (QUARANTINE_STATES) {
			final QuarantineState state = QUARANTINE_STATES.get(vehicleId);
			return state == null ? "NORMAL" : state.name();
		}
	}

	public static boolean renameSignal(String token, String deviceId, String signalId, String name) {
		final MinecraftServer current = server;
		if (current == null || !WebSessionManager.access(current, token, deviceId).canDispatch() || !name.matches("^$|^[A-Za-z0-9][A-Za-z0-9_\\-]{0,39}$")) {
			MtrbrDebugLog.event("WEB-SIGNAL-NAME", "actor=<forbidden> signal=" + signalId + " name=" + name + " accepted=false");
			return false;
		}
		final String actor = WebSessionManager.operator(current, token);
		for (final ServerLevel level : current.getAllLevels()) {
			final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
			final SignalFace face = ServerAspectManager.getFaceSnapshot(dimension).faces().get(signalId);
			if (face != null) {
				final RouteBindingsSavedData data = RouteBindingsSavedData.get(level);
				data.setSignalName(face.signalPos(), name);
				Network.CHANNEL.send(PacketDistributor.ALL.noArg(),
						new SyncRouteBindingsPacket(data.toClientMap(), data.getNodeBindings(), data.getIndicatorBindings(), data.getSignalNames()));
				MtrbrDebugLog.event("WEB-SIGNAL-NAME", "actor=" + actor + " signal=" + signalId + " pos=" + face.signalPos().getX() + ',' + face.signalPos().getY() + ',' + face.signalPos().getZ() + " name=" + name + " dimension=" + dimension + " accepted=true");
				return true;
			}
		}
		MtrbrDebugLog.event("WEB-SIGNAL-NAME", "actor=" + actor + " signal=" + signalId + " name=" + name + " accepted=false reason=SIGNAL_NOT_FOUND");
		return false;
	}

	private static JsonObject buildTopology(ServerLevel level, String dimension, Simulator simulator) {
		final JsonObject result = new JsonObject();
		result.addProperty("id", dimension);
		result.addProperty("revision", SectionStateManager.getTopologyRevision(simulator));
		final JsonArray rails = new JsonArray();
		final JsonArray platforms = new JsonArray();
		final Set<String> emittedRails = new HashSet<>();
		for (final Map.Entry<Position, ? extends Map<Position, Rail>> start : simulator.positionsToRail.entrySet()) {
			for (final Map.Entry<Position, Rail> end : start.getValue().entrySet()) {
				final Rail rail = end.getValue();
				if (!emittedRails.add(rail.getHexId())) continue;
				final JsonObject railJson = new JsonObject();
				railJson.addProperty("id", rail.getHexId());
				railJson.addProperty("platform", rail.isPlatform());
				final Map<Position, Rail> reverseConnections = simulator.positionsToRail.get(end.getKey());
				final Rail reverseRail = reverseConnections == null ? null : reverseConnections.get(start.getKey());
				railJson.addProperty("bidirectional", rail.getSpeedLimitMetersPerMillisecond(start.getKey()) > 0 && reverseRail != null && reverseRail.getSpeedLimitMetersPerMillisecond(end.getKey()) > 0);
				railJson.add("startNode", node(start.getKey()));
				railJson.add("endNode", node(end.getKey()));
				railJson.add("points", sampleRail(rail, start.getKey(), end.getKey()));
				rails.add(railJson);
				if (rail.isPlatform()) {
					final PlatformGeometry geometry = platformGeometry(level, rail);
					if (geometry.empty()) continue;
					final var platformData = nearestPlatform(simulator, rail);
					for (int index = 0; index < geometry.left().size(); index++) addPlatform(platforms, rail, platformData, 1, index, geometry.left().get(index));
					for (int index = 0; index < geometry.right().size(); index++) addPlatform(platforms, rail, platformData, -1, index, geometry.right().get(index));
				}
			}
		}
		result.add("rails", rails);
		result.add("platforms", platforms);
		final JsonArray signals = new JsonArray();
		final var faces = ServerAspectManager.getFaceSnapshot(dimension).faces().values();
		final Map<BlockPos, String> signalNames = RouteBindingsSavedData.get(level).getSignalNames();
		final Map<BlockPos, Integer> faceCounts = new java.util.HashMap<>();
		for (final SignalFace face : faces) faceCounts.merge(face.signalPos(), 1, Integer::sum);
		for (final SignalFace face : faces) {
			final double angle = Math.toRadians(face.travelAngle());
			final double sideOffset = faceCounts.get(face.signalPos()) > 1 ? (face.backSide() ? -.8 : .8) : 0;
			final JsonObject signal = new JsonObject();
			signal.addProperty("id", face.id());
			signal.addProperty("x", face.signalPos().getX() - Math.sin(angle) * sideOffset);
			signal.addProperty("z", face.signalPos().getZ() + Math.cos(angle) * sideOffset);
			signal.addProperty("signalX", face.signalPos().getX());
			signal.addProperty("signalY", face.signalPos().getY());
			signal.addProperty("signalZ", face.signalPos().getZ());
			signal.addProperty("angle", Math.round(face.travelAngle()));
			signal.addProperty("reverse", face.backSide());
			signal.addProperty("nodeX", face.nodePos().getX());
			signal.addProperty("nodeZ", face.nodePos().getZ());
			signal.addProperty("name", signalNames.getOrDefault(face.signalPos(), ""));
			signals.add(signal);
		}
		result.add("signals", signals);
		final JsonArray repeaterLinks = new JsonArray();
		final Set<String> repeaterLinkKeys = new HashSet<>();
		// Use the persisted indicator binding index rather than scanning block
		// positions. This also works when the repeater is outside the currently
		// loaded chunk set, and keeps the web view consistent after a refresh.
		for (final Map.Entry<BlockPos, BlockPos> binding : RouteBindingsSavedData.get(level).getIndicatorBindings().entrySet()) {
			final BlockPos repeaterPos = binding.getKey();
			final BlockPos signalPos = binding.getValue();
			if (!(level.getBlockEntity(repeaterPos) instanceof RepeatingSignalBlockEntity)) continue;
			final String key = signalPos.asLong() + ":" + repeaterPos.asLong();
			if (!repeaterLinkKeys.add(key)) continue;
			final JsonObject link = new JsonObject();
			link.addProperty("signalX", signalPos.getX() + .5);
			link.addProperty("signalZ", signalPos.getZ() + .5);
			link.addProperty("repeaterX", repeaterPos.getX() + .5);
			link.addProperty("repeaterZ", repeaterPos.getZ() + .5);
			repeaterLinks.add(link);
		}
		result.add("repeaterLinks", repeaterLinks);
		return result;
	}

	private static String lineFingerprint(Simulator simulator) {
		final StringBuilder value = new StringBuilder();
		final List<Depot> depots = new ArrayList<>(simulator.depots);
		depots.sort(java.util.Comparator.comparingLong(Depot::getId));
		for (final Depot depot : depots) {
			value.append(depot.getId()).append(':');
			for (final PathData segment : depot.getPath()) {
				value.append(segment.getHexId(false)).append('@').append(segment.getStartDistance()).append('-').append(segment.getEndDistance())
						.append(':').append(segment.getSavedRailBaseId()).append(':').append(segment.getDwellTime()).append(';');
			}
			value.append('|');
		}
		return value.toString();
	}

	private static String depotFingerprint(Depot depot) {
		final StringBuilder value = new StringBuilder(Long.toUnsignedString(depot.getId(), 16));
		for (final PathData segment : depot.getPath()) value.append('|').append(segment.getHexId(false)).append('@').append(segment.getStartDistance()).append('-').append(segment.getEndDistance()).append(':').append(segment.getSavedRailBaseId()).append(':').append(segment.getDwellTime()).append(':').append(segment.getStopIndex());
		return Integer.toUnsignedString(value.toString().hashCode(), 16);
	}

	private static JsonObject buildLines(String dimension, Simulator simulator) {
		final JsonObject result = new JsonObject();
		result.addProperty("id", dimension);
		final JsonArray depots = new JsonArray();
		final List<Depot> orderedDepots = new ArrayList<>(simulator.depots);
		orderedDepots.sort(java.util.Comparator.comparing(Depot::getName).thenComparingLong(Depot::getId));
		for (final Depot depot : orderedDepots) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", Long.toUnsignedString(depot.getId(), 16).toUpperCase(java.util.Locale.ROOT));
			entry.addProperty("name", depot.getName());
			entry.addProperty("fingerprint", depotFingerprint(depot));
			final JsonArray nodes = DepotPathEditorService.editorNodesJson(depot.getPath());
			final JsonArray segments = new JsonArray();
			PathData previous = null;
			for (int index = 0; index < depot.getPath().size(); index++) {
				final PathData segment = depot.getPath().get(index);
				final JsonObject segmentJson = new JsonObject();
				segmentJson.addProperty("index", index);
				segmentJson.addProperty("rail", segment.getHexId(false));
				segmentJson.add("start", position(segment, 0));
				segmentJson.add("end", position(segment, segment.getRailLength()));
				segmentJson.addProperty("length", segment.getRailLength());
				segmentJson.addProperty("platform", segment.getRail().isPlatform());
				segmentJson.addProperty("direction", segment.reversePositions ? -1 : 1);
				final Position travelStart = travelStart(segment);
				final Position travelEnd = travelEnd(segment);
				segmentJson.add("fromNode", node(travelStart));
				segmentJson.add("toNode", node(travelEnd));
				segmentJson.addProperty("disconnected", previous != null && !sameEnd(previous, segment));
				segmentJson.add("points", samplePath(segment));
				segments.add(segmentJson);
				previous = segment;
			}
			entry.add("nodes", nodes);
			entry.add("segments", segments);
			depots.add(entry);
		}
		result.add("depots", depots);
		return result;
	}

	private static Position travelStart(PathData segment) {
		return segment.reversePositions ? segment.getOrderedPosition2() : segment.getOrderedPosition1();
	}

	private static Position travelEnd(PathData segment) {
		return segment.reversePositions ? segment.getOrderedPosition1() : segment.getOrderedPosition2();
	}

	private static boolean sameEnd(PathData previous, PathData next) {
		final Position end = travelEnd(previous);
		final Position start = travelStart(next);
		return end.getX() == start.getX() && end.getY() == start.getY() && end.getZ() == start.getZ();
	}

	private static JsonArray position(PathData segment, double distance) {
		final Vector point = segment.getPosition(distance);
		final JsonArray result = new JsonArray();
		result.add(point.x);
		result.add(point.z);
		return result;
	}

	private static JsonObject node(Position position) {
		final JsonObject result = new JsonObject();
		result.addProperty("x", position.getX());
		result.addProperty("y", position.getY());
		result.addProperty("z", position.getZ());
		return result;
	}

	private static JsonArray samplePath(PathData segment) {
		final double length = segment.getRailLength();
		final int pointCount = Math.max(2, Math.min(64, (int) Math.ceil(length / 2) + 1));
		final JsonArray points = new JsonArray();
		for (int index = 0; index < pointCount; index++) points.add(position(segment, length * index / (pointCount - 1)));
		return points;
	}

	private static org.mtr.core.data.Platform nearestPlatform(Simulator simulator, Rail rail) {
		final Vector center = rail.railMath.getPosition(rail.railMath.getLength() / 2, false);
		org.mtr.core.data.Platform result = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (final var platform : simulator.platforms) {
			final var position = platform.getMidPosition();
			final double distance = Math.hypot(center.x - position.getX(), center.z - position.getZ());
			if (distance < bestDistance && distance <= 8) {
				bestDistance = distance;
				result = platform;
			}
		}
		return result;
	}

	private static void addPlatform(JsonArray platforms, Rail rail, org.mtr.core.data.Platform platformData, int side, int index, PlatformInterval interval) {
		final JsonObject platform = new JsonObject();
		platform.addProperty("id", rail.getHexId() + (side > 0 ? ":left:" : ":right:") + index);
		platform.addProperty("name", platformData == null ? "" : platformData.getStationName());
		platform.addProperty("number", platformData == null ? "" : platformData.getName());
		platform.addProperty("side", side);
		platform.add("points", sampleRail(rail, interval.start(), interval.end()));
		platforms.add(platform);
	}

	/**
	 * Projects actual platform blocks onto rail arc length. Positive tangent cross
	 * offset is the canonical left side, matching the web canvas offset convention.
	 */
	private static PlatformGeometry platformGeometry(ServerLevel level, Rail rail) {
		final double length = rail.railMath.getLength();
		if (length <= 0) return PlatformGeometry.emptyGeometry();
		final RailBounds bounds = railBounds(rail, length);
		final int padding = (int) Math.ceil(PLATFORM_MAX_LATERAL_DISTANCE) + 1;
		final Set<BlockPos> blocks = PlatformGeometryCache.platformBlocksInBounds(level, bounds.minX() - padding, bounds.maxX() + padding, bounds.minZ() - padding, bounds.maxZ() + padding);
		final List<PlatformCandidate> left = new ArrayList<>();
		final List<PlatformCandidate> right = new ArrayList<>();
		int rejectedTooNear = 0;
		int rejectedTooFar = 0;
		int rejectedHeight = 0;
		for (final BlockPos block : blocks) {
			final RailProjection projection = projectToRail(rail, length, block.getX() + .5, block.getZ() + .5);
			if (Math.abs(block.getY() - projection.point().y) > 1.5) {
				rejectedHeight++;
				continue;
			}
			final double tangentLength = Math.hypot(projection.tangentX(), projection.tangentZ());
			if (tangentLength < 1.0E-6) continue;
			final double offsetX = block.getX() + .5 - projection.point().x;
			final double offsetZ = block.getZ() + .5 - projection.point().z;
			final double cross = projection.tangentX() * offsetZ - projection.tangentZ() * offsetX;
			final double lateralDistance = Math.abs(cross) / tangentLength;
			if (lateralDistance < PLATFORM_MIN_LATERAL_DISTANCE) {
				rejectedTooNear++;
				continue;
			}
			if (lateralDistance > PLATFORM_MAX_LATERAL_DISTANCE) {
				rejectedTooFar++;
				continue;
			}
			final double halfLength = Math.max(.5, (Math.abs(projection.tangentX()) + Math.abs(projection.tangentZ())) / (2 * tangentLength));
			final PlatformCandidate candidate = new PlatformCandidate(Math.max(0, projection.s() - halfLength), Math.min(length, projection.s() + halfLength));
			if (cross >= 0) left.add(candidate); else right.add(candidate);
		}
		final List<PlatformInterval> leftIntervals = mergeIntervals(left);
		final List<PlatformInterval> rightIntervals = mergeIntervals(right);
		org.mtrbr.server.MtrbrDebugLog.event("PLATFORM-DIAGNOSTIC", "rail=" + rail.getHexId() + " loadedBlocks=" + blocks.size()
				+ " left=" + leftIntervals + " right=" + rightIntervals + " rejectedNear=" + rejectedTooNear
				+ " rejectedFar=" + rejectedTooFar + " rejectedHeight=" + rejectedHeight);
		return new PlatformGeometry(leftIntervals, rightIntervals);
	}

	private static RailBounds railBounds(Rail rail, double length) {
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		final int samples = Math.max(2, Math.min(2048, (int) Math.ceil(length) + 1));
		for (int index = 0; index < samples; index++) {
			final Vector point = rail.railMath.getPosition(length * index / (samples - 1), false);
			minX = Math.min(minX, (int) Math.floor(point.x));
			maxX = Math.max(maxX, (int) Math.ceil(point.x));
			minZ = Math.min(minZ, (int) Math.floor(point.z));
			maxZ = Math.max(maxZ, (int) Math.ceil(point.z));
		}
		return new RailBounds(minX, maxX, minZ, maxZ);
	}

	private static RailProjection projectToRail(Rail rail, double length, double x, double z) {
		final int samples = Math.max(3, Math.min(4096, (int) Math.ceil(length / .5) + 1));
		double bestS = 0;
		double bestDistanceSquared = Double.POSITIVE_INFINITY;
		for (int index = 0; index < samples; index++) {
			final double s = length * index / (samples - 1);
			final Vector point = rail.railMath.getPosition(s, false);
			final double distanceSquared = squaredDistance(point, x, z);
			if (distanceSquared < bestDistanceSquared) {
				bestS = s;
				bestDistanceSquared = distanceSquared;
			}
		}
		double low = Math.max(0, bestS - length / (samples - 1));
		double high = Math.min(length, bestS + length / (samples - 1));
		for (int iteration = 0; iteration < 12; iteration++) {
			final double first = low + (high - low) / 3;
			final double second = high - (high - low) / 3;
			if (squaredDistance(rail.railMath.getPosition(first, false), x, z) <= squaredDistance(rail.railMath.getPosition(second, false), x, z)) high = second; else low = first;
		}
		final double s = (low + high) / 2;
		final Vector point = rail.railMath.getPosition(s, false);
		final double tangentSample = Math.min(.25, Math.max(.01, length / 100));
		final Vector before = rail.railMath.getPosition(Math.max(0, s - tangentSample), false);
		final Vector after = rail.railMath.getPosition(Math.min(length, s + tangentSample), false);
		return new RailProjection(s, point, after.x - before.x, after.z - before.z);
	}

	private static double squaredDistance(Vector point, double x, double z) {
		final double dx = point.x - x;
		final double dz = point.z - z;
		return dx * dx + dz * dz;
	}

	private static List<PlatformInterval> mergeIntervals(List<PlatformCandidate> candidates) {
		if (candidates.isEmpty()) return List.of();
		final List<PlatformCandidate> sorted = candidates.stream()
				.map(candidate -> new PlatformCandidate(
						Math.max(0, Math.min(candidate.start(), candidate.end())),
						Math.max(0, Math.max(candidate.start(), candidate.end()))))
				.filter(candidate -> candidate.end() - candidate.start() > 1.0E-4)
				.sorted(Comparator.comparingDouble(PlatformCandidate::start)).toList();
		if (sorted.isEmpty()) return List.of();
		final List<PlatformInterval> intervals = new ArrayList<>();
		double start = sorted.get(0).start();
		double end = sorted.get(0).end();
		for (int index = 1; index < sorted.size(); index++) {
			final PlatformCandidate candidate = sorted.get(index);
			if (candidate.start() <= end + PLATFORM_INTERVAL_MERGE_GAP) {
				end = Math.max(end, candidate.end());
			} else {
				intervals.add(new PlatformInterval(start, end));
				start = candidate.start();
				end = candidate.end();
			}
		}
		intervals.add(new PlatformInterval(start, end));
		// A second normalization pass removes overlaps introduced by projection
		// rounding and guarantees monotonic, non-empty output intervals.
		final List<PlatformInterval> normalized = new ArrayList<>();
		for (final PlatformInterval interval : intervals) {
			if (interval.end() - interval.start() <= 1.0E-4) continue;
			if (!normalized.isEmpty() && interval.start() <= normalized.get(normalized.size() - 1).end() + 1.0E-4) {
				final PlatformInterval previous = normalized.remove(normalized.size() - 1);
				normalized.add(new PlatformInterval(previous.start(), Math.max(previous.end(), interval.end())));
			} else {
				normalized.add(interval);
			}
		}
		return List.copyOf(normalized);
	}

	private record RailBounds(int minX, int maxX, int minZ, int maxZ) {
	}

	private record RailProjection(double s, Vector point, double tangentX, double tangentZ) {
	}

	private record PlatformCandidate(double start, double end) {
	}

	private record PlatformInterval(double start, double end) {
	}

	private record PlatformGeometry(List<PlatformInterval> left, List<PlatformInterval> right) {
		private static PlatformGeometry emptyGeometry() {
			return new PlatformGeometry(List.of(), List.of());
		}

		private boolean empty() {
			return left.isEmpty() && right.isEmpty();
		}
	}

	private record TopologyCacheEntry(Simulator simulator, long topologyRevision, long platformRevision, JsonObject json) {
		private boolean matches(Simulator otherSimulator, long otherTopologyRevision, long otherPlatformRevision) {
			return simulator == otherSimulator && topologyRevision == otherTopologyRevision && platformRevision == otherPlatformRevision;
		}
	}

	private record LineCacheEntry(Simulator simulator, long topologyRevision, String fingerprint, JsonObject json) {
		private boolean matches(Simulator otherSimulator, long otherTopologyRevision, String otherFingerprint) {
			return simulator == otherSimulator && topologyRevision == otherTopologyRevision && fingerprint.equals(otherFingerprint);
		}
	}

	private static JsonObject buildState(String dimension, Simulator simulator) {
		final JsonObject result = new JsonObject();
		result.addProperty("id", dimension);
		result.addProperty("revision", SectionStateManager.getStateRevision(simulator));
		final JsonArray sections = new JsonArray();
		final Map<String, SectionStateManager.SectionSnapshot> sectionStates = SectionStateManager.getPublishedSections(simulator);
		// A vehicle label is displayed only on a Section that the authoritative
		// occupancy snapshot says it currently occupies. Among those Sections, use
		// the one nearest to the train head so a long train appears at its front.
		final Map<Long, String> displaySections = new java.util.HashMap<>();
		for (final RouteRequestManager.VehicleSnapshot vehicle : RouteRequestManager.getVehicleSnapshots(simulator)) {
			final java.util.Set<String> occupiedSectionIds = sectionStates.values().stream()
					.filter(section -> section.occupiedBy.contains(vehicle.vehicleId()))
					.map(section -> section.sectionId)
					.collect(java.util.stream.Collectors.toSet());
			final String sectionId = frontOccupiedSection(vehicle.path(), vehicle.head(), occupiedSectionIds);
			if (!sectionId.isEmpty()) displaySections.put(vehicle.vehicleId(), sectionId);
		}
		for (final SectionStateManager.SectionSnapshot section : sectionStates.values()) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", section.sectionId);
			entry.addProperty("occupied", !section.occupiedBy.isEmpty());
			entry.addProperty("reserved", !section.reservedBy.isEmpty());
			entry.addProperty("locked", !section.lockedBy.isEmpty());
			final JsonArray vehicles = new JsonArray();
			final JsonArray vehicleIds = new JsonArray();
			displaySections.keySet().stream().sorted().forEach(vehicleId -> {
				if (section.sectionId.equals(displaySections.get(vehicleId))) {
					vehicles.add(RouteRequestManager.getVehicleCode(vehicleId));
					vehicleIds.add(Long.toString(vehicleId));
				}
			});
			entry.add("vehicles", vehicles);
			entry.add("vehicleIds", vehicleIds);
			sections.add(entry);
		}
		result.add("sections", sections);
		// Signal indications change with authorization and occupancy, so they belong
		// to the live state document rather than the cached topology document.
		final JsonObject signalAspects = new JsonObject();
		for (final SignalFace face : ServerAspectManager.getFaceSnapshot(dimension).faces().values()) {
			final ServerAspect aspect = ServerAspectManager.get(simulator, face.signalPos(), face.backSide());
			signalAspects.addProperty(face.id(), aspect == null ? "UNKNOWN" : aspect.name());
		}
		result.add("signalAspects", signalAspects);
		final Map<Long, RouteRequestManager.VehicleSnapshot> vehicles = new java.util.HashMap<>();
		for (final RouteRequestManager.VehicleSnapshot vehicle : RouteRequestManager.getVehicleSnapshots(simulator)) vehicles.put(vehicle.vehicleId(), vehicle);
		final JsonArray requests = new JsonArray();
		for (final RouteRequestManager.RequestSnapshot request : RouteRequestManager.getRequestSnapshots(simulator)) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("vehicleId", Long.toString(request.vehicleId()));
			entry.addProperty("code", request.vehicleCode());
			entry.addProperty("state", request.oneShotOverride() ? "OVERRIDE" : request.state().name());
			entry.addProperty("route", request.routeName());
			entry.addProperty("next", request.nextStation());
			entry.addProperty("destination", request.destination());
			entry.addProperty("quarantineState", quarantineState(request.vehicleId()));
			final JsonArray requestSections = new JsonArray();
			final RouteRequestManager.VehicleSnapshot vehicle = vehicles.get(request.vehicleId());
			if (vehicle != null && vehicle.path() != null) {
				for (final var section : vehicle.path().getSections()) {
					if (section.endDistance() > request.head() && section.startDistance() < request.endDistance()) requestSections.add(section.sectionId());
				}
			}
			entry.add("sections", requestSections);
			requests.add(entry);
		}
		result.add("requests", requests);
		return result;
	}

	private static String frontOccupiedSection(org.mtrbr.server.PathSnapshot path, double head, java.util.Set<String> occupiedSectionIds) {
		if (path == null || occupiedSectionIds.isEmpty()) return "";
		final var sections = path.getSections();
		String closestSection = "";
		double closestDistance = Double.POSITIVE_INFINITY;
		double closestStart = Double.NEGATIVE_INFINITY;
		for (final var section : sections) {
			if (!occupiedSectionIds.contains(section.sectionId())) continue;
			final double distance = head < section.startDistance() ? section.startDistance() - head : head - section.endDistance();
			final double distanceToSection = Math.max(0, distance);
			if (distanceToSection < closestDistance - 1.0E-6
					|| (Math.abs(distanceToSection - closestDistance) <= 1.0E-6 && section.startDistance() > closestStart)) {
				closestDistance = distanceToSection;
				closestStart = section.startDistance();
				closestSection = section.sectionId();
			}
		}
		return closestSection;
	}

	private static JsonArray sampleRail(Rail rail) {
		return sampleRail(rail, 0, rail.railMath.getLength());
	}

	/** Matches visual endpoints to the directed positionsToRail entry used by the editor. */
	private static JsonArray sampleRail(Rail rail, Position start, Position end) {
		final JsonArray points = sampleRail(rail);
		if (points.size() < 2) return points;
		final Vector first = rail.railMath.getPosition(0, false);
		final double startDistance = squaredDistance(first, start.getX(), start.getZ());
		final double endDistance = squaredDistance(first, end.getX(), end.getZ());
		if (endDistance < startDistance) {
			final JsonArray reversed = new JsonArray();
			for (int index = points.size() - 1; index >= 0; index--) reversed.add(points.get(index));
			return reversed;
		}
		return points;
	}

	private static JsonArray sampleRail(Rail rail, double startDistance, double endDistance) {
		final JsonArray points = new JsonArray();
		final double start = Math.max(0, Math.min(rail.railMath.getLength(), startDistance));
		final double end = Math.max(start, Math.min(rail.railMath.getLength(), endDistance));
		final double length = end - start;
		// Sampling MTR's RailMath densely preserves its native Bezier/arc geometry in the web view.
		final int pointCount = Math.max(2, Math.min(1024, (int) Math.ceil(length) + 1));
		for (int i = 0; i < pointCount; i++) {
			final Vector point = rail.railMath.getPosition(start + length * i / (pointCount - 1), false);
			final JsonArray coordinate = new JsonArray();
			coordinate.add(point.x);
			coordinate.add(point.z);
			points.add(coordinate);
		}
		return points;
	}
}
