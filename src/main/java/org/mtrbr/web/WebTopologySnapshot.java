package org.mtrbr.web;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.mod.block.BlockPlatform;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtrbr.server.SectionStateManager;
import org.mtrbr.server.RouteRequestManager;
import org.mtrbr.server.ServerAspect;
import org.mtrbr.server.ServerAspectManager;
import org.mtrbr.server.SignalFace;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable JSON documents published by the server thread for the MTR webserver. */
public final class WebTopologySnapshot {
	private static volatile String topologyJson = "{\"dimensions\":[]}";
	private static volatile String stateJson = "{\"dimensions\":[]}";
	private static volatile MinecraftServer server;

	private WebTopologySnapshot() {
	}

	public static void publish(MinecraftServer server) {
		WebTopologySnapshot.server = server;
		final JsonArray topologyDimensions = new JsonArray();
		final JsonArray stateDimensions = new JsonArray();
		for (final ServerLevel level : server.getAllLevels()) {
			final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
			final Simulator simulator = SectionStateManager.getSimulator(dimension);
			if (simulator == null) continue;
			topologyDimensions.add(buildTopology(level, dimension, simulator));
			stateDimensions.add(buildState(dimension, simulator));
		}
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
		topologyJson = topology.toString();
		stateJson = state.toString();
	}

	public static String topologyJson() {
		return topologyJson;
	}

	public static String stateJson() {
		return stateJson;
	}

	public static void reset() {
		topologyJson = "{\"dimensions\":[]}";
		stateJson = "{\"dimensions\":[]}";
		server = null;
	}

	public static boolean canDispatch(String token) {
		final MinecraftServer current = server;
		return current != null && WebSessionManager.touchAndCanDispatch(current, token);
	}

	public static boolean dispatch(String token, String action, long vehicleId) {
		final MinecraftServer current = server;
		if (current == null || !WebSessionManager.touchAndCanDispatch(current, token)) return false;
		for (final ServerLevel level : current.getAllLevels()) {
			final String dimension = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
			final Simulator simulator = SectionStateManager.getSimulator(dimension);
			if (simulator == null || RouteRequestManager.getRequestSnapshots(simulator).stream().noneMatch(entry -> entry.vehicleId() == vehicleId)) continue;
			switch (action) {
				case "approve" -> RouteRequestManager.approveWaiting(simulator, vehicleId);
				case "revoke" -> RouteRequestManager.revokePendingAuthorization(simulator, vehicleId);
				case "override" -> RouteRequestManager.grantOneShotOverride(simulator, vehicleId);
				default -> { return false; }
			}
			return true;
		}
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
				railJson.add("points", sampleRail(rail));
				rails.add(railJson);
				if (rail.isPlatform()) {
					final JsonObject platform = new JsonObject();
					platform.addProperty("id", rail.getHexId());
					platform.addProperty("name", nearestPlatformName(simulator, rail));
					platform.addProperty("side", platformSide(level, rail));
					platform.add("points", sampleRail(rail));
					platforms.add(platform);
				}
			}
		}
		result.add("rails", rails);
		result.add("platforms", platforms);
		final JsonArray signals = new JsonArray();
		final var faces = ServerAspectManager.getFaceSnapshot(dimension).faces().values();
		final Map<BlockPos, Integer> faceCounts = new java.util.HashMap<>();
		for (final SignalFace face : faces) faceCounts.merge(face.signalPos(), 1, Integer::sum);
		for (final SignalFace face : faces) {
			final double angle = Math.toRadians(face.travelAngle());
			final double sideOffset = faceCounts.get(face.signalPos()) > 1 ? (face.backSide() ? -.8 : .8) : 0;
			final JsonObject signal = new JsonObject();
			signal.addProperty("id", face.id());
			signal.addProperty("x", face.signalPos().getX() - Math.sin(angle) * sideOffset);
			signal.addProperty("z", face.signalPos().getZ() + Math.cos(angle) * sideOffset);
			signal.addProperty("angle", Math.round(face.travelAngle()));
			signal.addProperty("reverse", face.backSide());
			final ServerAspect aspect = ServerAspectManager.get(level, face.signalPos(), face.backSide());
			signal.addProperty("aspect", aspect == null ? "UNKNOWN" : aspect.name());
			signals.add(signal);
		}
		result.add("signals", signals);
		return result;
	}

	private static String nearestPlatformName(Simulator simulator, Rail rail) {
		final Vector center = rail.railMath.getPosition(rail.railMath.getLength() / 2, false);
		String name = "";
		double bestDistance = Double.POSITIVE_INFINITY;
		for (final var platform : simulator.platforms) {
			final var position = platform.getMidPosition();
			final double distance = Math.hypot(center.x - position.getX(), center.z - position.getZ());
			if (distance < bestDistance && distance <= 8) {
				bestDistance = distance;
				name = platform.getStationName();
			}
		}
		return name;
	}

	private static int platformSide(ServerLevel level, Rail rail) {
		final double length = rail.railMath.getLength();
		int left = 0;
		int right = 0;
		final int samples = Math.max(3, Math.min(24, (int) Math.ceil(length / 4)));
		for (int index = 0; index < samples; index++) {
			final double distance = length * index / (samples - 1);
			final Vector point = rail.railMath.getPosition(distance, false);
			final Vector before = rail.railMath.getPosition(Math.max(0, distance - 0.5), false);
			final Vector after = rail.railMath.getPosition(Math.min(length, distance + 0.5), false);
			final double dx = after.x - before.x;
			final double dz = after.z - before.z;
			final double normalLength = Math.hypot(dx, dz);
			if (normalLength < 1.0E-6) continue;
			for (int offset = 2; offset <= 5; offset++) {
				left += platformBlocksAt(level, point, -dz / normalLength * offset, dx / normalLength * offset);
				right += platformBlocksAt(level, point, dz / normalLength * offset, -dx / normalLength * offset);
			}
		}
		return left == right ? 1 : left > right ? 1 : -1;
	}

	private static int platformBlocksAt(ServerLevel level, Vector point, double offsetX, double offsetZ) {
		final BlockPos base = BlockPos.containing(point.x + offsetX, point.y, point.z + offsetZ);
		for (int y = -1; y <= 1; y++) {
			if (level.getBlockState(base.offset(0, y, 0)).getBlock() instanceof BlockPlatform) return 1;
		}
		return 0;
	}

	private static JsonObject buildState(String dimension, Simulator simulator) {
		final JsonObject result = new JsonObject();
		result.addProperty("id", dimension);
		result.addProperty("revision", SectionStateManager.getStateRevision(simulator));
		final JsonArray sections = new JsonArray();
		// Vehicle labels use the live path position rather than occupancy. Occupancy
		// intentionally has a boundary gap while a train crosses between sections.
		final Map<Long, String> displaySections = new java.util.HashMap<>();
		for (final RouteRequestManager.VehicleSnapshot vehicle : RouteRequestManager.getVehicleSnapshots(simulator)) {
			final String sectionId = frontSection(vehicle.path(), vehicle.head());
			if (!sectionId.isEmpty()) displaySections.put(vehicle.vehicleId(), sectionId);
		}
		for (final SectionStateManager.SectionSnapshot section : SectionStateManager.getPublishedSections(simulator).values()) {
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
					vehicleIds.add(vehicleId);
				}
			});
			entry.add("vehicles", vehicles);
			entry.add("vehicleIds", vehicleIds);
			sections.add(entry);
		}
		result.add("sections", sections);
		final Map<Long, RouteRequestManager.VehicleSnapshot> vehicles = new java.util.HashMap<>();
		for (final RouteRequestManager.VehicleSnapshot vehicle : RouteRequestManager.getVehicleSnapshots(simulator)) vehicles.put(vehicle.vehicleId(), vehicle);
		final JsonArray requests = new JsonArray();
		for (final RouteRequestManager.RequestSnapshot request : RouteRequestManager.getRequestSnapshots(simulator)) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("vehicleId", request.vehicleId());
			entry.addProperty("code", request.vehicleCode());
			entry.addProperty("state", request.oneShotOverride() ? "OVERRIDE" : request.state().name());
			entry.addProperty("route", request.routeName());
			entry.addProperty("next", request.nextStation());
			entry.addProperty("destination", request.destination());
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

	private static String frontSection(org.mtrbr.server.PathSnapshot path, double head) {
		if (path == null) return "";
		final var sections = path.getSections();
		String closestSection = "";
		double closestDistance = Double.POSITIVE_INFINITY;
		for (int index = 0; index < sections.size(); index++) {
			final var section = sections.get(index);
			if (head >= section.startDistance() - 1.0E-6 && (head < section.endDistance() - 1.0E-6 || index == sections.size() - 1)) {
				return section.sectionId();
			}
			final double distance = head < section.startDistance() ? section.startDistance() - head : head - section.endDistance();
			if (distance < closestDistance) {
				closestDistance = distance;
				closestSection = section.sectionId();
			}
		}
		return closestSection;
	}

	private static JsonArray sampleRail(Rail rail) {
		final JsonArray points = new JsonArray();
		final double length = rail.railMath.getLength();
		// Sampling MTR's RailMath densely preserves its native Bezier/arc geometry in the web view.
		final int pointCount = Math.max(2, Math.min(1024, (int) Math.ceil(length) + 1));
		for (int i = 0; i < pointCount; i++) {
			final Vector point = rail.railMath.getPosition(length * i / (pointCount - 1), false);
			final JsonArray coordinate = new JsonArray();
			coordinate.add(point.x);
			coordinate.add(point.z);
			points.add(coordinate);
		}
		return points;
	}
}
