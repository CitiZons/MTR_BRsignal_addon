package org.mtrbr.web;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtrbr.server.SectionStateManager;
import org.mtrbr.server.MtrbrDebugLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Rebuilds a Depot path from an ordered draft of real MTR rail-graph nodes. */
public final class DepotPathEditorService {
	private static final int MAX_VISITED_NODES = 16_384;

	private DepotPathEditorService() {
	}

	public static JsonObject saveNodes(MinecraftServer server, String token, String deviceId, JsonObject request) {
		if (server == null) return loggedPathResult("WEB-PATH-SAVE", "<server-not-ready>", request, failure("SERVER_NOT_READY"));
		if (!WebSessionManager.access(server, token, deviceId).canDispatch()) return loggedPathResult("WEB-PATH-SAVE", "<forbidden>", request, failure("FORBIDDEN"));
		final String actor = WebSessionManager.operator(server, token);
		final Simulator simulator = simulator(server, request);
		if (simulator == null) return loggedPathResult("WEB-PATH-SAVE", actor, request, failure("DIMENSION_NOT_FOUND"));
		return loggedPathResult("WEB-PATH-SAVE", actor, request, onSimulationThread(simulator, () -> saveNodes(simulator, request)));
	}

	public static JsonObject previewNodes(MinecraftServer server, String token, String deviceId, JsonObject request) {
		if (server == null) return loggedPathResult("WEB-PATH-PREVIEW", "<server-not-ready>", request, failure("SERVER_NOT_READY"));
		if (!WebSessionManager.access(server, token, deviceId).canDispatch()) return loggedPathResult("WEB-PATH-PREVIEW", "<forbidden>", request, failure("FORBIDDEN"));
		final String actor = WebSessionManager.operator(server, token);
		final Simulator simulator = simulator(server, request);
		if (simulator == null) return loggedPathResult("WEB-PATH-PREVIEW", actor, request, failure("DIMENSION_NOT_FOUND"));
		return loggedPathResult("WEB-PATH-PREVIEW", actor, request, onSimulationThread(simulator, () -> previewNodes(simulator, request)));
	}

	private static JsonObject saveNodes(Simulator simulator, JsonObject request) {
		final PreparedEdit prepared = prepare(simulator, request);
		if (prepared.failure() != null) return prepared.failure();
		final Depot depot = prepared.depot();
		final SolveResult solved = solve(prepared.graph(), prepared.nodes());
		if (solved.failure() != null) return solved.failure();

		final ObjectArrayList<PathData> originalPath = new ObjectArrayList<>();
		for (final PathData segment : depot.getPath()) originalPath.add(new PathData(segment, segment.getStartDistance(), segment.getEndDistance()));
		final ObjectArrayList<PathData> rebuilt = new ObjectArrayList<>();
		for (final DirectedRail edge : solved.edges()) rebuilt.add(pathData(edge, depot.getPath()));

		if (!continuous(rebuilt)) return failure("PATH_DISCONNECTED");
		SidingPathFinder.generatePathDataDistances(rebuilt, 0);
		try {
			depot.getPath().clear();
			depot.getPath().addAll(rebuilt);
			depot.writePathCache();
			depot.generatePlatformDirectionsAndWriteDeparturesToSidings();
			depot.updateGenerationStatus(simulator.getCurrentMillis(), Depot.GeneratedStatus.SUCCESSFUL, 0, 0);
			simulator.save();
		} catch (RuntimeException exception) {
			depot.getPath().clear();
			depot.getPath().addAll(originalPath);
			depot.writePathCache();
			return failure("PATH_SAVE_FAILED");
		}

		final JsonObject result = success();
		result.addProperty("fingerprint", fingerprint(depot));
		result.add("segments", segments(depot.getPath()));
		result.add("nodes", editorNodesJson(depot.getPath()));
		return result;
	}

	private static JsonObject previewNodes(Simulator simulator, JsonObject request) {
		final PreparedEdit prepared = prepare(simulator, request);
		if (prepared.failure() != null) return prepared.failure();
		final SolveResult solved = solve(prepared.graph(), prepared.nodes());
		if (solved.failure() != null) return solved.failure();
		final JsonObject result = success();
		result.addProperty("segmentCount", solved.edges().size());
		return result;
	}

	private static PreparedEdit prepare(Simulator simulator, JsonObject request) {
		final Depot depot = depot(simulator, string(request, "depotId"));
		if (depot == null) return PreparedEdit.failure(failure("DEPOT_NOT_FOUND"));
		if (!fingerprint(depot).equals(string(request, "fingerprint"))) return PreparedEdit.failure(failure("PATH_CHANGED"));
		final List<Position> originalNodes = editorPositions(depot.getPath());
		final List<Position> nodes = requestNodes(request);
		if (nodes.size() < 2) return PreparedEdit.failure(failure("PATH_TOO_SHORT"));
		if (originalNodes.size() < 2 || !nodes.get(0).equals(originalNodes.get(0)) || !nodes.get(nodes.size() - 1).equals(originalNodes.get(originalNodes.size() - 1))) return PreparedEdit.failure(failure("ENDPOINT_EDIT_UNSUPPORTED"));
		final List<DirectedRail> graph = graph(simulator, depot.getPath());
		final Set<Position> graphNodes = new HashSet<>();
		for (final DirectedRail edge : graph) {
			graphNodes.add(edge.start());
			graphNodes.add(edge.end());
		}
		if (!graphNodes.containsAll(nodes)) return PreparedEdit.failure(failure("NODE_NOT_FOUND"));
		return new PreparedEdit(depot, nodes, graph, null);
	}

	/** Resolves every waypoint as one constrained route, retaining alternate arrival directions. */
	private static SolveResult solve(List<DirectedRail> graph, List<Position> nodes) {
		final Map<Position, List<DirectedRail>> outgoing = new HashMap<>();
		for (final DirectedRail edge : graph) outgoing.computeIfAbsent(edge.start(), ignored -> new ArrayList<>()).add(edge);
		final Map<Angle, PlannedRoute> states = new HashMap<>();
		states.put(null, new PlannedRoute(null, 0, List.of()));
		for (int index = 1; index < nodes.size(); index++) {
			final Position start = nodes.get(index - 1);
			final Position end = nodes.get(index);
			if (start.equals(end)) return SolveResult.failure(pathFailure("REPEATED_NODE", index - 1, index, start, end));
			final Map<Angle, PlannedRoute> nextStates = new HashMap<>();
			for (final PlannedRoute state : states.values()) {
				for (final Leg leg : findLegs(outgoing, start, state.arrivalFacing(), end)) {
					final PlannedRoute candidate = state.extend(leg);
					final PlannedRoute previous = nextStates.get(candidate.arrivalFacing());
					if (previous == null || candidate.cost() < previous.cost()) nextStates.put(candidate.arrivalFacing(), candidate);
				}
			}
			if (nextStates.isEmpty()) return SolveResult.failure(pathFailure("NO_PATH_BETWEEN_NODES", index - 1, index, start, end));
			states.clear();
			states.putAll(nextStates);
		}
		return SolveResult.success(states.values().stream().min(Comparator.comparingDouble(PlannedRoute::cost)).orElseThrow().edges());
	}

	private static List<Leg> findLegs(Map<Position, List<DirectedRail>> outgoing, Position start, Angle startFacing, Position end) {
		final PriorityQueue<SearchNode> queue = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::cost));
		final Map<StateKey, SearchNode> visited = new HashMap<>();
		final Map<Angle, SearchNode> arrivals = new HashMap<>();
		final SearchNode root = new SearchNode(start, startFacing, 0, null, null);
		queue.add(root);
		visited.put(new StateKey(start, startFacing), root);
		while (!queue.isEmpty() && visited.size() <= MAX_VISITED_NODES) {
			final SearchNode current = queue.remove();
			if (visited.get(new StateKey(current.position(), current.facing())) != current) continue;
			if (current.edge() != null && current.position().equals(end)) {
				arrivals.putIfAbsent(current.facing(), current);
				continue;
			}
			for (final DirectedRail edge : outgoing.getOrDefault(current.position(), List.of())) {
				if (!edge.existingPathEdge() && edge.rail().getSpeedLimitMetersPerMillisecond(edge.start()) <= 0) continue;
				final Angle railFacing = edge.rail().getStartAngle(edge.start());
				if (current.facing() != null && current.facing() != railFacing && !edge.rail().canTurnBack()) continue;
				final Angle nextFacing = edge.rail().getStartAngle(edge.end()).getOpposite();
				final SearchNode next = new SearchNode(edge.end(), nextFacing, current.cost() + edge.rail().railMath.getLength(), current, edge);
				final StateKey key = new StateKey(next.position(), next.facing());
				final SearchNode previous = visited.get(key);
				if (previous == null || next.cost() < previous.cost()) {
					visited.put(key, next);
					queue.add(next);
				}
			}
		}
		final List<Leg> result = new ArrayList<>();
		for (final SearchNode arrival : arrivals.values()) result.add(new Leg(arrival.facing(), arrival.cost(), reconstruct(arrival)));
		return result;
	}

	private static List<DirectedRail> reconstruct(SearchNode node) {
		final List<DirectedRail> result = new ArrayList<>();
		for (SearchNode current = node; current.previous() != null; current = current.previous()) result.add(current.edge());
		java.util.Collections.reverse(result);
		return result;
	}

	private static List<DirectedRail> graph(Simulator simulator, List<PathData> existingPath) {
		final List<DirectedRail> result = new ArrayList<>();
		for (final Map.Entry<Position, ? extends Map<Position, Rail>> entry : simulator.positionsToRail.entrySet()) {
			for (final Map.Entry<Position, Rail> connection : entry.getValue().entrySet()) result.add(new DirectedRail(entry.getKey(), connection.getKey(), connection.getValue(), false));
		}
		// The live graph cache can omit an already-generated depot edge while it is
		// being refreshed. Keep those MTR-accepted directed edges available to the
		// editor solver so unchanged route portions stay traversable.
		for (final PathData segment : existingPath) result.add(new DirectedRail(segment.getOrderedPosition1(), segment.getOrderedPosition2(), segment.getRail(), true));
		return result;
	}

	private static PathData pathData(DirectedRail edge, List<PathData> original) {
		for (final PathData segment : original) {
			if (segment.getRail() == edge.rail() && segment.getOrderedPosition1().equals(edge.start()) && segment.getOrderedPosition2().equals(edge.end())) return new PathData(segment, segment.getStartDistance(), segment.getEndDistance());
		}
		for (final PathData segment : original) {
			if (segment.getRail() == edge.rail()) {
				return new PathData(edge.rail(), segment.getSavedRailBaseId(), segment.getDwellTime(), segment.getStopIndex(), 0, 0, edge.start(), edge.rail().getStartAngle(edge.start()), edge.end(), edge.rail().getStartAngle(edge.end()).getOpposite());
			}
		}
		return new PathData(edge.rail(), 0, 0, -1, 0, 0, edge.start(), edge.rail().getStartAngle(edge.start()), edge.end(), edge.rail().getStartAngle(edge.end()).getOpposite());
	}

	private static boolean continuous(List<PathData> path) {
		for (int index = 1; index < path.size(); index++) {
			final Position previous = path.get(index - 1).getOrderedPosition2();
			final Position next = path.get(index).getOrderedPosition1();
			if (Math.abs(previous.getX() - next.getX()) > 0.1 || Math.abs(previous.getY() - next.getY()) > 0.1 || Math.abs(previous.getZ() - next.getZ()) > 0.1) return false;
		}
		return !path.isEmpty();
	}

	/**
	 * PathData records rail boundaries, which may include a zero-length handoff at
	 * a junction. The editor needs waypoints, so only consecutive duplicates are
	 * collapsed; a later revisit to the same node remains a valid turnback/loop.
	 */
	private static List<Position> editorPositions(List<PathData> path) {
		return editorNodes(path).stream().map(EditorNode::position).toList();
	}

	static JsonArray editorNodesJson(List<PathData> path) {
		final JsonArray result = new JsonArray();
		for (final EditorNode editorNode : editorNodes(path)) {
			final JsonObject node = new JsonObject();
			node.addProperty("x", editorNode.position().getX());
			node.addProperty("y", editorNode.position().getY());
			node.addProperty("z", editorNode.position().getZ());
			node.addProperty("displayX", editorNode.displayX());
			node.addProperty("displayZ", editorNode.displayZ());
			result.add(node);
		}
		return result;
	}

	private static List<EditorNode> editorNodes(List<PathData> path) {
		if (path.isEmpty()) return List.of();
		final List<EditorNode> result = new ArrayList<>();
		final PathData first = path.get(0);
		final var firstPosition = first.getPosition(0);
		result.add(new EditorNode(first.getOrderedPosition1(), firstPosition.x, firstPosition.z));
		for (final PathData segment : path) {
			final Position position = segment.getOrderedPosition2();
			if (!result.get(result.size() - 1).position().equals(position)) {
				final var display = segment.getPosition(segment.getRailLength());
				result.add(new EditorNode(position, display.x, display.z));
			}
		}
		return List.copyOf(result);
	}

	private static List<Position> requestNodes(JsonObject request) {
		if (!request.has("nodes") || !request.get("nodes").isJsonArray()) return List.of();
		final List<Position> result = new ArrayList<>();
		for (final JsonElement element : request.getAsJsonArray("nodes")) {
			if (!element.isJsonObject()) return List.of();
			final JsonObject node = element.getAsJsonObject();
			if (!node.has("x") || !node.has("y") || !node.has("z")) return List.of();
			result.add(new Position(node.get("x").getAsInt(), node.get("y").getAsInt(), node.get("z").getAsInt()));
		}
		return List.copyOf(result);
	}


	private static JsonArray segments(List<PathData> path) {
		final JsonArray result = new JsonArray();
		for (int index = 0; index < path.size(); index++) {
			final PathData segment = path.get(index);
			final JsonObject value = new JsonObject();
			value.addProperty("index", index);
			value.addProperty("rail", segment.getHexId(false));
			value.addProperty("platform", segment.getRail().isPlatform());
			value.add("points", points(segment));
			result.add(value);
		}
		return result;
	}

	private static JsonArray points(PathData segment) {
		final int count = Math.max(2, Math.min(64, (int) Math.ceil(segment.getRailLength() / 2) + 1));
		final JsonArray result = new JsonArray();
		for (int index = 0; index < count; index++) {
			final var point = segment.getPosition(segment.getRailLength() * index / (count - 1));
			final JsonArray coordinate = new JsonArray();
			coordinate.add(point.x);
			coordinate.add(point.z);
			result.add(coordinate);
		}
		return result;
	}

	private static Depot depot(Simulator simulator, String id) {
		try {
			return simulator.depotIdMap.get(Long.parseUnsignedLong(id, 16));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static Simulator simulator(MinecraftServer server, JsonObject request) {
		final String dimension = string(request, "dimension");
		for (final ServerLevel level : server.getAllLevels()) {
			final String id = level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
			if (id.equals(dimension)) return SectionStateManager.getSimulator(id);
		}
		return null;
	}

	private static JsonObject onSimulationThread(Simulator simulator, java.util.function.Supplier<JsonObject> action) {
		final CompletableFuture<JsonObject> result = new CompletableFuture<>();
		simulator.run(() -> {
			try {
				result.complete(action.get());
			} catch (RuntimeException exception) {
				result.complete(failure("EDITOR_ERROR"));
			}
		});
		try {
			return result.get(2, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			return failure("EDITOR_BUSY");
		}
	}

	private static String fingerprint(Depot depot) {
		final StringBuilder value = new StringBuilder(Long.toUnsignedString(depot.getId(), 16));
		for (final PathData segment : depot.getPath()) value.append('|').append(segment.getHexId(false)).append('@').append(segment.getStartDistance()).append('-').append(segment.getEndDistance()).append(':').append(segment.getSavedRailBaseId()).append(':').append(segment.getDwellTime()).append(':').append(segment.getStopIndex());
		return Integer.toUnsignedString(value.toString().hashCode(), 16);
	}

	private static String string(JsonObject object, String key) {
		return object.has(key) ? object.get(key).getAsString() : "";
	}

	private static JsonObject loggedPathResult(String category, String actor, JsonObject request, JsonObject result) {
		final StringBuilder detail = new StringBuilder("actor=").append(actor)
				.append(" dimension=").append(string(request, "dimension"))
				.append(" depot=").append(string(request, "depotId"))
				.append(" fingerprint=").append(string(request, "fingerprint"))
				.append(" nodes=").append(formatNodes(request))
				.append(" ok=").append(result.has("ok") && result.get("ok").getAsBoolean());
		if (result.has("error")) detail.append(" error=").append(result.get("error").getAsString());
		if (result.has("reason")) detail.append(" reason=").append(result.get("reason").getAsString());
		if (result.has("fromIndex")) detail.append(" fromIndex=").append(result.get("fromIndex").getAsInt());
		if (result.has("toIndex")) detail.append(" toIndex=").append(result.get("toIndex").getAsInt());
		if (result.has("from")) detail.append(" from=").append(result.get("from"));
		if (result.has("to")) detail.append(" to=").append(result.get("to"));
		if (result.has("segmentCount")) detail.append(" solvedSegments=").append(result.get("segmentCount").getAsInt());
		MtrbrDebugLog.event(category, detail.toString());
		return result;
	}

	private static String formatNodes(JsonObject request) {
		if (!request.has("nodes") || !request.get("nodes").isJsonArray()) return "[]";
		final StringBuilder result = new StringBuilder("[");
		for (final JsonElement element : request.getAsJsonArray("nodes")) {
			if (result.length() > 1) result.append(" -> ");
			if (!element.isJsonObject()) {
				result.append("<invalid>");
				continue;
			}
			final JsonObject node = element.getAsJsonObject();
			result.append(node.has("x") ? node.get("x").getAsInt() : "?")
					.append(',').append(node.has("y") ? node.get("y").getAsInt() : "?")
					.append(',').append(node.has("z") ? node.get("z").getAsInt() : "?");
		}
		return result.append(']').toString();
	}

	private static JsonObject success() {
		final JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		return result;
	}

	private static JsonObject failure(String code) {
		final JsonObject result = new JsonObject();
		result.addProperty("ok", false);
		result.addProperty("error", code);
		return result;
	}

	private static JsonObject pathFailure(String reason, int fromIndex, int toIndex, Position from, Position to) {
		final JsonObject result = failure(reason);
		result.addProperty("reason", reason);
		result.addProperty("fromIndex", fromIndex);
		result.addProperty("toIndex", toIndex);
		result.add("from", position(from));
		result.add("to", position(to));
		return result;
	}

	private static JsonObject position(Position position) {
		final JsonObject result = new JsonObject();
		result.addProperty("x", position.getX());
		result.addProperty("y", position.getY());
		result.addProperty("z", position.getZ());
		return result;
	}

	private record DirectedRail(Position start, Position end, Rail rail, boolean existingPathEdge) {
	}
	private record StateKey(Position position, Angle facing) {
	}
	private record SearchNode(Position position, Angle facing, double cost, SearchNode previous, DirectedRail edge) {
	}
	private record EditorNode(Position position, double displayX, double displayZ) {
	}
	private record PreparedEdit(Depot depot, List<Position> nodes, List<DirectedRail> graph, JsonObject failure) {
		private static PreparedEdit failure(JsonObject failure) {
			return new PreparedEdit(null, List.of(), List.of(), failure);
		}
	}
	private record Leg(Angle arrivalFacing, double cost, List<DirectedRail> edges) {
	}
	private record PlannedRoute(Angle arrivalFacing, double cost, List<DirectedRail> edges) {
		private PlannedRoute extend(Leg leg) {
			final List<DirectedRail> combined = new ArrayList<>(edges);
			combined.addAll(leg.edges());
			return new PlannedRoute(leg.arrivalFacing(), cost + leg.cost(), List.copyOf(combined));
		}
	}
	private record SolveResult(List<DirectedRail> edges, JsonObject failure) {
		private static SolveResult success(List<DirectedRail> edges) {
			return new SolveResult(edges, null);
		}
		private static SolveResult failure(JsonObject failure) {
			return new SolveResult(List.of(), failure);
		}
	}
}
