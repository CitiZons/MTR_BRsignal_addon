package org.mtrbr.web;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;
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
import org.mtrbr.data.ManualDepotPathSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Rebuilds a Depot path from an ordered draft of real MTR rail-graph nodes. */
public final class DepotPathEditorService {
	private static final int MAX_VISITED_NODES = 16_384;
	private static final Set<Simulator> RESTORED_SIMULATORS = Collections.newSetFromMap(new WeakHashMap<>());

	private DepotPathEditorService() {
	}

	public static JsonObject saveNodes(MinecraftServer server, String token, String deviceId, JsonObject request) {
		if (server == null) return loggedPathResult("WEB-PATH-SAVE", "<server-not-ready>", request, failure("SERVER_NOT_READY"));
		if (!WebSessionManager.access(server, token, deviceId).canDispatch()) return loggedPathResult("WEB-PATH-SAVE", "<forbidden>", request, failure("FORBIDDEN"));
		final String actor = WebSessionManager.operator(server, token);
		final Simulator simulator = simulator(server, request);
		if (simulator == null) return loggedPathResult("WEB-PATH-SAVE", actor, request, failure("DIMENSION_NOT_FOUND"));
		final ServerLevel level = level(server, request);
		if (level == null) return loggedPathResult("WEB-PATH-SAVE", actor, request, failure("DIMENSION_NOT_FOUND"));
		return loggedPathResult("WEB-PATH-SAVE", actor, request, onSimulationThread(simulator, () -> saveNodes(simulator, level, request)));
	}

	public static JsonObject previewNodes(MinecraftServer server, String token, String deviceId, JsonObject request) {
		if (server == null) return loggedPathResult("WEB-PATH-PREVIEW", "<server-not-ready>", request, failure("SERVER_NOT_READY"));
		if (!WebSessionManager.access(server, token, deviceId).canDispatch()) return loggedPathResult("WEB-PATH-PREVIEW", "<forbidden>", request, failure("FORBIDDEN"));
		final String actor = WebSessionManager.operator(server, token);
		final Simulator simulator = simulator(server, request);
		if (simulator == null) return loggedPathResult("WEB-PATH-PREVIEW", actor, request, failure("DIMENSION_NOT_FOUND"));
		return loggedPathResult("WEB-PATH-PREVIEW", actor, request, onSimulationThread(simulator, () -> previewNodes(simulator, request)));
	}

	private static JsonObject saveNodes(Simulator simulator, ServerLevel level, JsonObject request) {
		final PreparedEdit prepared = prepare(simulator, request);
		if (prepared.failure() != null) return prepared.failure();
		final Depot depot = prepared.depot();
		final SolveResult solved = solve(prepared.graph(), prepared.nodes(), prepared.depot().getPath());
		if (solved.failure() != null) return solved.failure();
		final JsonObject directedFailure = directedContinuityFailure(solved.edges(), depot.getPath());
		if (directedFailure != null) return directedFailure;

		if (!applySolvedPath(simulator, depot, solved.edges())) return failure("PATH_SAVE_FAILED");
		ManualDepotPathSavedData.get(level).setNodes(depot.getId(), prepared.nodes());
		ManualDepotPathSavedData.get(level).setSections(depot.getId(), solved.edges().stream().map(edge -> edge.rail().getHexId()).toList());
		simulator.save();

		final JsonObject result = success();
		result.addProperty("fingerprint", fingerprint(depot));
		result.add("segments", segments(depot.getPath()));
		result.add("nodes", editorNodesJson(depot.getPath()));
		return result;
	}

	/**
	 * Invoked after MTR has reconstructed the platform sequence, but before it
	 * starts rebuilding siding-to-main-route paths. This keeps MTR's platform
	 * metadata while making its generated vehicles use the editor route.
	 */
	public static boolean restorePersistedPath(Depot depot, String source) {
		final Simulator simulator = simulator(depot);
		if (simulator == null) return false;
		final ServerLevel level = level(simulator);
		if (level == null) return false;
		return restorePersistedPath(simulator, level, depot, source);
	}

	/** Restores manual overlays once after a Simulator becomes available post-load. */
	public static void restorePersistedPaths(Simulator simulator) {
		final ServerLevel level = level(simulator);
		if (level == null) return;
		synchronized (RESTORED_SIMULATORS) {
			if (!RESTORED_SIMULATORS.add(simulator)) return;
		}
		for (final Depot depot : simulator.depots) restorePersistedPath(simulator, level, depot, "SIMULATOR_LOAD");
	}

	private static boolean restorePersistedPath(Simulator simulator, ServerLevel level, Depot depot, String source) {
		return restorePersistedNodes(simulator, depot, ManualDepotPathSavedData.get(level).getNodes(depot.getId()), source);
	}

	private static boolean restorePersistedNodes(Simulator simulator, Depot depot, List<Position> nodes, String source) {
		// No overlay means MTR retains sole ownership of the native generated path.
		if (nodes.size() < 2) return false;
		final SolveResult solved = solve(graph(simulator, depot.getPath()), nodes, depot.getPath());
		if (solved.failure() != null || directedContinuityFailure(solved.edges(), depot.getPath()) != null) {
			MtrbrDebugLog.event("MANUAL-DEPOT-PATH", "source=" + source + " depot=" + Long.toUnsignedString(depot.getId(), 16) + " result=REJECTED");
			return false;
		}
		final boolean applied = applySolvedPath(simulator, depot, solved.edges());
		MtrbrDebugLog.event("MANUAL-DEPOT-PATH", "source=" + source + " depot=" + Long.toUnsignedString(depot.getId(), 16) + " result=" + (applied ? "APPLIED" : "FAILED") + " nodes=" + nodes.size());
		return applied;
	}

	private static boolean applySolvedPath(Simulator simulator, Depot depot, List<DirectedRail> edges) {
		final ObjectArrayList<PathData> originalPath = new ObjectArrayList<>();
		for (final PathData segment : depot.getPath()) originalPath.add(new PathData(segment, segment.getStartDistance(), segment.getEndDistance()));
		final ObjectArrayList<PathData> rebuilt;
		try {
			rebuilt = rebuildPath(depot.getPath(), edges);
		} catch (RuntimeException exception) {
			return false;
		}
		if (rebuilt == null) return false;
		try {
			depot.getPath().clear();
			depot.getPath().addAll(rebuilt);
			depot.writePathCache();
			depot.updateGenerationStatus(simulator.getCurrentMillis(), Depot.GeneratedStatus.SUCCESSFUL, 0, 0);
			return true;
		} catch (RuntimeException exception) {
			depot.getPath().clear();
			depot.getPath().addAll(originalPath);
			depot.writePathCache();
			return false;
		}
	}

	/** Validate the actual MTR representation before any path/cache/status is replaced. */
	private static ObjectArrayList<PathData> rebuildPath(List<PathData> original, List<DirectedRail> edges) {
		if (directedContinuityFailure(edges, original) != null) return null;
		final ObjectArrayList<PathData> rebuilt = new ObjectArrayList<>();
		for (final DirectedRail edge : edges) rebuilt.add(pathData(edge, original));
		for (int index = 0; index + 1 < rebuilt.size(); index++) {
			final PathData before = rebuilt.get(index);
			if (before.isOppositeRail(rebuilt.get(index + 1)) && before.getDwellTime() <= 0) {
				// MTR's automatic stopping-index scan requires positive dwell even on a
				// turnback rail. Native SidingPathFinder uses 1 for a non-platform reverse.
				final PathData stop = original.stream().filter(segment -> segment.getRail() == before.getRail()
						&& segment.getDwellTime() > 0).findFirst().orElse(before);
				rebuilt.set(index, directedPathData(edges.get(index), stop.getSavedRailBaseId(),
						Math.max(1, stop.getDwellTime()), stop.getStopIndex()));
			}
		}
		SidingPathFinder.generatePathDataDistances(rebuilt, 0);
		return matchesDirectedPath(rebuilt, edges) ? rebuilt : null;
	}

	private static boolean matchesDirectedPath(List<PathData> path, List<DirectedRail> edges) {
		if (path.size() != edges.size() || !continuous(path)) return false;
		for (int index = 0; index < path.size(); index++) {
			final PathData segment = path.get(index);
			final DirectedRail edge = edges.get(index);
			if (segment.getRail() != edge.rail() || !travelStart(segment).equals(edge.start()) || !travelEnd(segment).equals(edge.end())) return false;
		}
		return true;
	}

	private static JsonObject previewNodes(Simulator simulator, JsonObject request) {
		final PreparedEdit prepared = prepare(simulator, request);
		if (prepared.failure() != null) return prepared.failure();
		final SolveResult solved = solve(prepared.graph(), prepared.nodes(), prepared.depot().getPath());
		if (solved.failure() != null) return solved.failure();
		if (rebuildPath(prepared.depot().getPath(), solved.edges()) == null) return failure("PATH_DISCONNECTED");
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
		if (!graphNodes.containsAll(nodes)) return PreparedEdit.failure(failure("SECTION_NOT_FOUND"));
		return new PreparedEdit(depot, nodes, graph, null);
	}

	/** Resolves every waypoint as one constrained route, retaining alternate arrival directions. */
	private static SolveResult solve(List<DirectedRail> graph, List<Position> nodes, List<PathData> original) {
		final Set<Rail> scheduledStops = scheduledStops(original);
		final Map<Position, List<DirectedRail>> outgoing = new HashMap<>();
		for (final DirectedRail edge : graph) outgoing.computeIfAbsent(edge.start(), ignored -> new ArrayList<>()).add(edge);
		final Map<RouteState, PlannedRoute> states = new HashMap<>();
		states.put(new RouteState(null, null), new PlannedRoute(null, null, 0, List.of()));
		for (int index = 1; index < nodes.size(); index++) {
			final Position start = nodes.get(index - 1);
			final Position end = nodes.get(index);
			if (start.equals(end)) return SolveResult.failure(pathFailure("REPEATED_NODE", index - 1, index, start, end));
			final Map<RouteState, PlannedRoute> nextStates = new HashMap<>();
			for (final PlannedRoute state : states.values()) {
				for (final Leg leg : findLegs(outgoing, start, state.edges().isEmpty() ? null : state.edges().get(state.edges().size() - 1), end, scheduledStops)) {
					final PlannedRoute candidate = state.extend(leg);
					final PlannedRoute previous = nextStates.get(new RouteState(candidate.arrivalSectionId(), candidate.arrivalFacing()));
					if (previous == null || candidate.cost() < previous.cost()) nextStates.put(new RouteState(candidate.arrivalSectionId(), candidate.arrivalFacing()), candidate);
				}
			}
			if (nextStates.isEmpty()) return SolveResult.failure(pathFailure("SECTION_CHAIN_DISCONNECTED", index - 1, index, start, end));
			states.clear();
			states.putAll(nextStates);
		}
		return SolveResult.success(states.values().stream().min(Comparator.comparingDouble(PlannedRoute::cost)).orElseThrow().edges());
	}

	private static List<Leg> findLegs(Map<Position, List<DirectedRail>> outgoing, Position start, DirectedRail incoming, Position end, Set<Rail> scheduledStops) {
		final Angle startFacing = incoming == null ? null : incoming.rail().getStartAngle(start).getOpposite();
		final PriorityQueue<SearchNode> queue = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::cost));
		final Map<StateKey, SearchNode> visited = new HashMap<>();
		final Map<RouteState, SearchNode> arrivals = new HashMap<>();
		final SearchNode root = new SearchNode(start, startFacing, 0, null, incoming);
		queue.add(root);
		visited.put(new StateKey(start, incoming == null ? "" : incoming.sectionId(), startFacing), root);
		while (!queue.isEmpty() && visited.size() <= MAX_VISITED_NODES) {
			final SearchNode current = queue.remove();
			if (visited.get(new StateKey(current.position(), current.edge() == null ? "" : current.edge().sectionId(), current.facing())) != current) continue;
			if (current.previous() != null && current.position().equals(end)) {
				arrivals.putIfAbsent(new RouteState(current.edge().sectionId(), current.facing()), current);
				continue;
			}
			for (final DirectedRail edge : outgoing.getOrDefault(current.position(), List.of())) {
				if (!edge.existingPathEdge() && edge.rail().getSpeedLimitMetersPerMillisecond(edge.start()) <= 0) continue;
				// Keep the incoming rail across waypoint boundaries: a waypoint is not a reversal.
				if (!canFollow(current.edge(), edge, scheduledStops)) continue;
				final Angle nextFacing = edge.rail().getStartAngle(edge.end()).getOpposite();
				final SearchNode next = new SearchNode(edge.end(), nextFacing, current.cost() + edge.rail().railMath.getLength(), current, edge);
				final StateKey key = new StateKey(next.position(), next.edge().sectionId(), next.facing());
				final SearchNode previous = visited.get(key);
				if (previous == null || next.cost() < previous.cost()) {
					visited.put(key, next);
					queue.add(next);
				}
			}
		}
		final List<Leg> result = new ArrayList<>();
		for (final SearchNode arrival : arrivals.values()) result.add(new Leg(arrival.edge().sectionId(), arrival.facing(), arrival.cost(), reconstruct(arrival)));
		return result;
	}

	private static List<DirectedRail> reconstruct(SearchNode node) {
		final List<DirectedRail> result = new ArrayList<>();
		for (SearchNode current = node; current.previous() != null; current = current.previous()) result.add(current.edge());
		java.util.Collections.reverse(result);
		return result;
	}

	private static List<DirectedRail> graph(Simulator simulator, List<PathData> ignoredExistingPath) {
		final List<DirectedRail> result = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		for (final Map.Entry<Position, ? extends Map<Position, Rail>> entry : simulator.positionsToRail.entrySet()) {
			for (final Map.Entry<Position, Rail> connection : entry.getValue().entrySet()) {
				final DirectedRail edge = new DirectedRail(entry.getKey(), connection.getKey(), connection.getValue(), false);
				if (seen.add(edgeKey(edge))) result.add(edge);
			}
		}
		return result;
	}

	private static String edgeKey(DirectedRail edge) {
		return edge.rail().getHexId() + ":" + edge.start().getX() + "," + edge.start().getY() + "," + edge.start().getZ() + "->" + edge.end().getX() + "," + edge.end().getY() + "," + edge.end().getZ();
	}

	private static PathData pathData(DirectedRail edge, List<PathData> original) {
		for (final PathData segment : original) {
			if (segment.getRail() == edge.rail() && travelStart(segment).equals(edge.start()) && travelEnd(segment).equals(edge.end())) return new PathData(segment, segment.getStartDistance(), segment.getEndDistance());
		}
		for (final PathData segment : original) {
			if (segment.getRail() == edge.rail()) {
				return directedPathData(edge, segment.getSavedRailBaseId(), segment.getDwellTime(), segment.getStopIndex());
			}
		}
		return directedPathData(edge, 0, 0, -1);
	}

	private static PathData directedPathData(DirectedRail edge, long savedRailBaseId, long dwellTime, int stopIndex) {
		// MTR derives reversePositions and endpoint angles from the directed endpoints.
		// Sorting them here erases reverse travel and duplicates inbound platform legs.
		return new PathData(edge.rail(), savedRailBaseId, dwellTime, stopIndex, edge.start(), edge.end());
	}

	private static Position travelStart(PathData segment) {
		return segment.reversePositions ? segment.getOrderedPosition2() : segment.getOrderedPosition1();
	}

	private static Position travelEnd(PathData segment) {
		return segment.reversePositions ? segment.getOrderedPosition1() : segment.getOrderedPosition2();
	}

	private static boolean continuous(List<PathData> path) {
		for (int index = 1; index < path.size(); index++) {
			if (!travelEnd(path.get(index - 1)).equals(travelStart(path.get(index)))) return false;
		}
		return !path.isEmpty();
	}

	/** Scheduled platform/siding stops retain native dwell metadata when LINE is rebuilt. */
	private static Set<Rail> scheduledStops(List<PathData> original) {
		final Set<Rail> result = new HashSet<>();
		for (final PathData segment : original) {
			final Rail rail = segment.getRail();
			if (rail != null && segment.getDwellTime() > 0 && (rail.isPlatform() || rail.isSiding())) result.add(rail);
		}
		return result;
	}

	private static boolean canFollow(DirectedRail previous, DirectedRail next, Set<Rail> scheduledStops) {
		if (previous == null) return true;
		if (!previous.end().equals(next.start())) return false;
		// Compare local tangents at the shared node, not distant endpoint bearings.
		// B -> junction -> C is not a curve when B and C depart on the same side.
		if (previous.rail().getStartAngle(previous.end()).getOpposite() == next.rail().getStartAngle(next.start())) return true;
		// A real reversal retraces the SAME rail; canTurnBack is not a branch-jump permit.
		return previous.rail() == next.rail() && previous.start().equals(next.end())
				&& (next.rail().canTurnBack() || scheduledStops.contains(next.rail()));
	}

	private static JsonObject directedContinuityFailure(List<DirectedRail> edges, List<PathData> original) {
		final Set<Rail> stops = scheduledStops(original);
		for (int index = 1; index < edges.size(); index++) {
			final DirectedRail previous = edges.get(index - 1);
			final DirectedRail next = edges.get(index);
			if (!previous.end().equals(next.start())) return pathFailure("PATH_DISCONNECTED", index - 1, index, previous.end(), next.start());
			if (!canFollow(previous, next, stops)) return pathFailure("PATH_DIRECTION_MISMATCH", index - 1, index, previous.start(), next.end());
		}
		return edges.isEmpty() ? failure("PATH_DISCONNECTED") : null;
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
		result.add(new EditorNode(travelStart(first), firstPosition.x, firstPosition.z));
		for (final PathData segment : path) {
			final Position position = travelEnd(segment);
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
		final ServerLevel level = level(server, request);
		return level == null ? null : SectionStateManager.getSimulator(dimension(level));
	}

	private static ServerLevel level(MinecraftServer server, JsonObject request) {
		final String requestedDimension = string(request, "dimension");
		for (final ServerLevel level : server.getAllLevels()) if (dimension(level).equals(requestedDimension)) return level;
		return null;
	}

	private static ServerLevel level(Simulator simulator) {
		final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server == null) return null;
		for (final ServerLevel level : server.getAllLevels()) if (simulator.dimension.equals(dimension(level))) return level;
		return null;
	}

	private static Simulator simulator(Depot depot) {
		final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server == null) return null;
		for (final ServerLevel level : server.getAllLevels()) {
			final Simulator simulator = SectionStateManager.getSimulator(dimension(level));
			if (simulator != null && simulator.depotIdMap.get(depot.getId()) == depot) return simulator;
		}
		return null;
	}

	private static String dimension(ServerLevel level) {
		return level.dimension().location().getNamespace() + "/" + level.dimension().location().getPath();
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
		private String sectionId() { return rail.getHexId(); }
	}
	private record RouteState(String sectionId, Angle facing) {
	}
	private record StateKey(Position position, String sectionId, Angle facing) {
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
	private record Leg(String arrivalSectionId, Angle arrivalFacing, double cost, List<DirectedRail> edges) {
	}
	private record PlannedRoute(String arrivalSectionId, Angle arrivalFacing, double cost, List<DirectedRail> edges) {
		private PlannedRoute extend(Leg leg) {
			final List<DirectedRail> combined = new ArrayList<>(edges);
			combined.addAll(leg.edges());
			return new PlannedRoute(leg.arrivalSectionId(), leg.arrivalFacing(), cost + leg.cost(), List.copyOf(combined));
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
