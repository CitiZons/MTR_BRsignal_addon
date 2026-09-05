package org.mtrbr.web;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.loading.FMLPaths;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.path.SidingPathFinder;
import org.mtrbr.server.PathSnapshot;
import org.mtrbr.server.MovementGate;
import org.mtrbr.server.SectionStateManager;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.JsonWriter;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtrbr.data.ManualDepotPathSavedData;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Real MTR PathData/rail fixtures; no game, HTTP server, or user save is opened. */
public final class DepotPathRegression {
    private static final Position A = new Position(277, -60, -5);
    private static final Position B = new Position(298, -60, -5);
    private static final Position C = new Position(325, -60, -5);
    private static final List<Position> ROUND_TRIP = List.of(A, B, C, B, A);
    private static Simulator simulator;
    private static Rail running;
    private static Rail platform;
    private static int passed;

    public static void main(String[] args) throws Exception {
        FMLPaths.loadAbsolutePaths(Path.of(".").toAbsolutePath().normalize());
        simulator = new Simulator("line-test", new String[] { "line-test" }, Path.of("simulation"), false);
        running = Rail.newRail(A, Angle.E, B, Angle.W, Rail.Shape.QUADRATIC, 0,
                new ObjectArrayList<>(), 80, 80, false, false, false, false, false, TransportMode.TRAIN);
        platform = Rail.newPlatformRail(B, Angle.E, C, Angle.W, Rail.Shape.QUADRATIC, 0,
                new ObjectArrayList<>(), TransportMode.TRAIN);
        final Method railCache = Rail.class.getDeclaredMethod("writePositionsToRailCache", simulator.positionsToRail.getClass());
        railCache.setAccessible(true);
        railCache.invoke(running, simulator.positionsToRail);
        railCache.invoke(platform, simulator.positionsToRail);
        test("native reverse LINE nodes are directed and read-only", DepotPathRegression::nativeNodes);
        test("LINE starting in reverse retains both endpoints", DepotPathRegression::reverseFirst);
        test("new reverse PathData uses native endpoint angles and metadata", DepotPathRegression::newReverse);
        test("same rail reuse distinguishes inbound/outbound metadata", DepotPathRegression::reuseDirection);
        test("platform turnback survives rebuild, distance calculation and serialization", DepotPathRegression::roundTrip);
        test("wrong-direction and disconnected PathData are rejected", DepotPathRegression::validation);
        test("invalid replacement preserves native path and generation status", DepotPathRegression::invalidApply);
        test("LINE preview and waypoint insert/delete preserve route without writing", DepotPathRegression::preview);
        test("LINE endpoint and stale fingerprint guards remain enabled", DepotPathRegression::editGuards);
        test("saved LINE nodes survive NBT reload and repair legacy directed cache", DepotPathRegression::persistedRestore);
        test("MTR refresh re-applies the saved LINE route", DepotPathRegression::panelRefresh);
        test("no stored override leaves native path and zero-distance handoff untouched", DepotPathRegression::noOverride);
        test("three repeated LINE laps retain every platform reversal", DepotPathRegression::repeatedLaps);
        test("same-side turnout shortcut is rejected with sparse and dense waypoints", DepotPathRegression::turnoutShortcut);
        test("turnout stem and curved branch remain traversable in both directions", DepotPathRegression::turnoutLegal);
        test("waypoints cannot authorize reversal on ordinary track", DepotPathRegression::waypointReversal);
        test("turnback capability cannot authorize a jump to another branch", DepotPathRegression::turnoutCapability);
        test("invalid turnout replacement and persisted overlay leave native route intact", DepotPathRegression::turnoutRestore);
        test("branch-to-branch travel uses a real stem turnback rather than a node jump", DepotPathRegression::turnoutViaTurnback);
        test("new same-rail turnback receives native positive dwell metadata", DepotPathRegression::turnbackStop);
        test("cached same-side junction paths are detected but legal curves and reversals are not", DepotPathRegression::cachedJunctionGuard);
        test("runtime gate brakes old invalid paths and does not rewind them", DepotPathRegression::runtimeJunctionGuard);
        System.out.println("Depot LINE path regression: " + passed + " cases passed.");
    }

    private static void nativeNodes() throws Exception {
        final List<PathData> path = nativePath();
        final List<JsonObject> before = path.stream().map(DepotPathRegression::json).toList();
        equal(ROUND_TRIP, nodes(path), "native round trip nodes");
        equal(before, path.stream().map(DepotPathRegression::json).toList(), "export must not rewrite native path");
    }

    private static void reverseFirst() {
        equal(List.of(C, B), nodes(List.of(nativeSegment(platform, C, B, 12, 10000, 2))), "reverse first endpoints");
    }

    private static void newReverse() throws Exception {
        final Object edge = edge(platform, C, B);
        final PathData actual = (PathData) call("directedPathData", edge, 12L, 10000L, 2);
        final PathData expected = nativeSegment(platform, C, B, 12, 10000, 2);
        equal(json(expected), json(actual), "native angles, direction and stop metadata");
        check(actual.reversePositions, "C -> B must reverse");
        final PathData runningReverse = (PathData) call("pathData", edge(running, B, A), List.of());
        equal(json(nativeSegment(running, B, A, 0, 0, -1)), json(runningReverse), "new reverse non-platform leg");
    }

    private static void reuseDirection() throws Exception {
        final PathData outbound = nativeSegment(platform, C, B, 42, 20000, 7);
        final PathData inbound = nativeSegment(platform, B, C, 12, 10000, 1);
        final List<PathData> original = List.of(outbound, inbound);
        equal(json(inbound), json((PathData) call("pathData", edge(platform, B, C), original)), "do not reuse opposite occurrence");
        equal(json(outbound), json((PathData) call("pathData", edge(platform, C, B), original)), "retain exact reverse metadata");
        final PathData reversedFallback = (PathData) call("pathData", edge(platform, C, B), List.of(inbound));
        check(reversedFallback.reversePositions, "fallback must not duplicate inbound direction");
        equal(12L, reversedFallback.getSavedRailBaseId(), "fallback platform identity");
        equal(10000L, reversedFallback.getDwellTime(), "fallback dwell");
        equal(1, reversedFallback.getStopIndex(), "fallback stop metadata");
    }

    private static void roundTrip() throws Exception {
        final List<PathData> rebuilt = rebuild(nativePath(), solve(ROUND_TRIP));
        verifyPath(rebuilt, ROUND_TRIP);
        check(rebuilt.get(1).isOppositeRail(rebuilt.get(2)), "native platform reversal occurrence");
        double distance = 0;
        for (final PathData segment : rebuilt) {
            equal(distance, segment.getStartDistance(), "contiguous cumulative distances");
            distance += segment.getRailLength();
            equal(distance, segment.getEndDistance(), "full rail length");
            final PathData loaded = new PathData(new JsonReader(json(segment)));
            equal(segment.reversePositions, loaded.reversePositions, "serialized direction");
            equal(segment.getStopIndex(), loaded.getStopIndex(), "serialized stop");
        }
    }

    private static void validation() throws Exception {
        final List<Object> edges = List.of(edge(platform, B, C), edge(platform, C, B));
        final PathData inbound = nativeSegment(platform, B, C, 12, 10000, 1);
        check(!(boolean) call("matchesDirectedPath", List.of(inbound, inbound), edges), "legacy duplicate inbound must fail");
        check(!(boolean) call("matchesDirectedPath", List.of(inbound), List.of(edge(platform, C, B))), "single wrong-direction segment must fail too");
        check(!(boolean) call("matchesDirectedPath", List.of(), List.of()), "empty replacement must fail");
        check(rebuild(nativePath(), List.of(edge(running, A, B), edge(platform, C, B))) == null, "disconnected solved edges must fail");
    }

    private static void invalidApply() throws Exception {
        final Depot depot = depot(nativePath());
        final List<PathData> original = List.copyOf(depot.getPath());
        final long generated = depot.getLastGeneratedMillis();
        final var status = depot.getLastGeneratedStatus();
        check(!(boolean) call("applySolvedPath", simulator, depot, List.of(edge(running, A, B), edge(platform, C, B))), "invalid apply rejected");
        equal(original, List.copyOf(depot.getPath()), "no partial replacement");
        equal(generated, depot.getLastGeneratedMillis(), "no generation timestamp change");
        equal(status, depot.getLastGeneratedStatus(), "no false SUCCESSFUL");
    }

    private static void preview() throws Exception {
        final Depot depot = depot(nativePath());
        final List<PathData> original = List.copyOf(depot.getPath());
        final long generated = depot.getLastGeneratedMillis();
        for (final List<Position> draft : List.of(ROUND_TRIP, List.of(A, C, A))) {
            final JsonObject response = (JsonObject) call("previewNodes", simulator, request(depot, draft));
            check(response.get("ok").getAsBoolean(), "LINE preview supports inserting/deleting intermediate waypoint: " + response);
            equal(4, response.get("segmentCount").getAsInt(), "solver expands sparse waypoints");
            equal(original, List.copyOf(depot.getPath()), "preview must not write path");
            equal(generated, depot.getLastGeneratedMillis(), "preview must not write status");
        }
    }

    private static void editGuards() throws Exception {
        final Depot depot = depot(nativePath());
        final JsonObject stale = request(depot, ROUND_TRIP);
        stale.addProperty("fingerprint", "stale");
        equal("PATH_CHANGED", ((JsonObject) call("previewNodes", simulator, stale)).get("error").getAsString(), "stale draft rejected");
        equal("ENDPOINT_EDIT_UNSUPPORTED", ((JsonObject) call("previewNodes", simulator, request(depot, List.of(B, C, A)))).get("error").getAsString(), "endpoint remains protected");
    }

    private static void persistedRestore() throws Exception {
        final PathData in = nativeSegment(platform, B, C, 12, 10000, 1);
        final List<PathData> legacy = List.of(nativeSegment(running, A, B, 0, 0, 0), in, in,
                nativeSegment(running, A, B, 0, 0, 0));
        final Depot depot = depot(legacy);
        final ManualDepotPathSavedData saved = new ManualDepotPathSavedData();
        saved.setNodes(depot.getId(), ROUND_TRIP);
        saved.setSections(depot.getId(), List.of(running.getHexId(), platform.getHexId(), platform.getHexId(), running.getHexId()));
        final Method load = ManualDepotPathSavedData.class.getDeclaredMethod("load", CompoundTag.class);
        load.setAccessible(true);
        final ManualDepotPathSavedData loaded = (ManualDepotPathSavedData) load.invoke(null, saved.save(new CompoundTag()));
        equal(saved.getNodes(depot.getId()), loaded.getNodes(depot.getId()), "persisted LINE nodes unchanged");
        equal(saved.getSections(depot.getId()), loaded.getSections(depot.getId()), "persisted LINE sections unchanged");
        check((boolean) call("restorePersistedNodes", simulator, depot, loaded.getNodes(depot.getId()), "TEST_LOAD"), "replay succeeds");
        verifyPath(depot.getPath(), ROUND_TRIP);
        equal(Depot.GeneratedStatus.SUCCESSFUL, depot.getLastGeneratedStatus(), "validated restore is successful");
    }

    private static void panelRefresh() throws Exception {
        final Depot depot = depot(nativePath());
        final List<Position> saved = ROUND_TRIP;
        check((boolean) call("applySolvedPath", simulator, depot, solve(saved)), "LINE save core");
        verifyPath(depot.getPath(), saved);
        depot.getPath().clear();
        depot.getPath().addAll(nativePath().subList(0, 2)); // MTR regenerated only the outbound leg before restore.
        check((boolean) call("restorePersistedNodes", simulator, depot, saved, "TEST_PANEL_REFRESH"), "saved LINE overlay reapplied");
        verifyPath(depot.getPath(), saved);
    }

    private static void noOverride() throws Exception {
        final List<PathData> path = new ArrayList<>(nativePath());
        path.add(0, new PathData(path.get(0), 0, 0));
        final Depot depot = depot(path);
        final List<PathData> before = List.copyOf(depot.getPath());
        final var status = depot.getLastGeneratedStatus();
        check(!(boolean) call("restorePersistedNodes", simulator, depot, List.of(), "TEST_NO_OVERLAY"), "no automatic override");
        equal(before, List.copyOf(depot.getPath()), "native path and handoff objects untouched");
        equal(status, depot.getLastGeneratedStatus(), "native status untouched");
        equal(ROUND_TRIP, nodes(depot.getPath()), "only consecutive duplicate nodes collapsed");
    }

    private static void repeatedLaps() throws Exception {
        final List<Position> laps = new ArrayList<>();
        laps.add(A);
        for (int i = 0; i < 3; i++) laps.addAll(ROUND_TRIP.subList(1, ROUND_TRIP.size()));
        // Repeated laps also reverse at A: model that end as an actual turnback rail,
        // rather than implicitly permitting a U-turn on ordinary track at a waypoint.
        final Rail terminal = track(A, Angle.E, B, Angle.W, true);
        final List<Object> graph = List.of(edge(terminal, A, B), edge(terminal, B, A),
                edge(platform, B, C), edge(platform, C, B));
        final Object solved = call("solve", graph, laps, nativePath());
        equal(null, resultField(solved, "failure"), "repeated native turnbacks");
        @SuppressWarnings("unchecked") final List<Object> edges = (List<Object>) resultField(solved, "edges");
        final List<PathData> result = rebuild(nativePath(), edges);
        verifyPath(result, laps);
        for (int i = 0; i < 3; i++) check(result.get(4 * i + 1).isOppositeRail(result.get(4 * i + 2)), "lap " + i + " reversal");
    }

    private static final Position STEM = new Position(1000, -60, 0);
    private static final Position POINT = new Position(1020, -60, 0);
    private static final Position BRANCH_B = new Position(1040, -60, 0);
    private static final Position BRANCH_C = new Position(1040, -60, 20);

    private record Turnout(Rail stem, Rail b, Rail c, List<Object> graph) {}

    private static Turnout turnout(boolean turnbackBranch) throws Exception {
        final Rail stem = track(STEM, Angle.E, POINT, Angle.W, false);
        final Rail b = track(POINT, Angle.E, BRANCH_B, Angle.W, false);
        // Both branches depart east at POINT, even though C curves south afterwards.
        final Rail c = track(POINT, Angle.E, BRANCH_C, Angle.N, turnbackBranch);
        return new Turnout(stem, b, c, List.of(edge(stem, STEM, POINT), edge(stem, POINT, STEM),
                edge(b, POINT, BRANCH_B), edge(b, BRANCH_B, POINT),
                edge(c, POINT, BRANCH_C), edge(c, BRANCH_C, POINT)));
    }

    private static Rail track(Position start, Angle startAngle, Position end, Angle endAngle, boolean turnback) {
        return turnback ? Rail.newTurnBackRail(start, startAngle, end, endAngle, Rail.Shape.QUADRATIC, 0,
                new ObjectArrayList<>(), TransportMode.TRAIN)
                : Rail.newRail(start, startAngle, end, endAngle, Rail.Shape.QUADRATIC, 0,
                new ObjectArrayList<>(), 80, 80, false, false, false, false, false, TransportMode.TRAIN);
    }

    private static Object resultField(Object result, String name) throws Exception {
        final Method method = result.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(result);
    }

    private static void turnoutShortcut() throws Exception {
        final Turnout t = turnout(false);
        for (final List<Position> draft : List.of(List.of(BRANCH_B, BRANCH_C), List.of(BRANCH_B, POINT, BRANCH_C),
                List.of(BRANCH_C, BRANCH_B), List.of(BRANCH_C, POINT, BRANCH_B))) {
            final Object result = call("solve", t.graph(), draft, List.of());
            check(resultField(result, "failure") != null, "same-side shortcut must fail: " + draft);
        }
    }

    @SuppressWarnings("unchecked")
    private static void turnoutLegal() throws Exception {
        final Turnout t = turnout(false);
        for (final List<Position> draft : List.of(List.of(STEM, BRANCH_B), List.of(BRANCH_B, STEM),
                List.of(STEM, BRANCH_C), List.of(BRANCH_C, STEM), List.of(STEM, POINT, BRANCH_C))) {
            final Object result = call("solve", t.graph(), draft, List.of());
            equal(null, resultField(result, "failure"), "legal turnout passage");
            final List<PathData> path = rebuild(List.of(), (List<Object>) resultField(result, "edges"));
            equal(2, path.size(), "no invented detour/reversal");
            equal(List.of(draft.get(0), POINT, draft.get(draft.size() - 1)), nodes(path), "legal directed curve");
        }
    }

    private static void waypointReversal() throws Exception {
        final Turnout t = turnout(false);
        final Object result = call("solve", t.graph(), List.of(STEM, POINT, STEM), List.of());
        check(resultField(result, "failure") != null, "waypoint is not a turnback authorization");
        check(rebuild(List.of(), List.of(edge(t.stem(), STEM, POINT), edge(t.stem(), POINT, STEM))) == null,
                "final validation also rejects unplanned ordinary-track reversal");
    }

    private static void turnoutCapability() throws Exception {
        final Turnout t = turnout(true);
        check(t.c().canTurnBack(), "fixture has turnback-capable branch");
        check(rebuild(List.of(), List.of(edge(t.b(), BRANCH_B, POINT), edge(t.c(), POINT, BRANCH_C))) == null,
                "different-rail heading reversal remains invalid even on turnback rail");
    }

    private static void turnoutRestore() throws Exception {
        final Turnout t = turnout(false);
        final Method railCache = Rail.class.getDeclaredMethod("writePositionsToRailCache", simulator.positionsToRail.getClass());
        railCache.setAccessible(true);
        for (final Rail rail : List.of(t.stem(), t.b(), t.c())) railCache.invoke(rail, simulator.positionsToRail);
        final List<PathData> nativeRoute = List.of(nativeSegment(t.stem(), STEM, POINT, 0, 0, 0),
                nativeSegment(t.c(), POINT, BRANCH_C, 0, 0, 0));
        final Depot depot = depot(nativeRoute);
        final var status = depot.getLastGeneratedStatus();
        final long timestamp = depot.getLastGeneratedMillis();
        check(!(boolean) call("applySolvedPath", simulator, depot,
                List.of(edge(t.b(), BRANCH_B, POINT), edge(t.c(), POINT, BRANCH_C))), "invalid final route rejected");
        check(!(boolean) call("restorePersistedNodes", simulator, depot,
                List.of(BRANCH_B, POINT, BRANCH_C), "TEST_INVALID_TURNOUT"), "bad saved overlay rejected");
        equal(nativeRoute, List.copyOf(depot.getPath()), "no route replacement on rejection");
        equal(status, depot.getLastGeneratedStatus(), "no false successful status");
        equal(timestamp, depot.getLastGeneratedMillis(), "no generation timestamp change");
    }

    private static void turnoutViaTurnback() throws Exception {
        final Turnout t = turnout(false);
        final Rail terminal = track(STEM, Angle.E, POINT, Angle.W, true);
        final List<Object> graph = List.of(edge(terminal, STEM, POINT), edge(terminal, POINT, STEM),
                edge(t.b(), POINT, BRANCH_B), edge(t.b(), BRANCH_B, POINT),
                edge(t.c(), POINT, BRANCH_C), edge(t.c(), BRANCH_C, POINT));
        for (final List<Position> draft : List.of(List.of(BRANCH_B, BRANCH_C), List.of(BRANCH_B, POINT, BRANCH_C))) {
            final Object solved = call("solve", graph, draft, List.of());
            equal(null, resultField(solved, "failure"), "real stem reversal is reachable");
            @SuppressWarnings("unchecked") final List<Object> edges = (List<Object>) resultField(solved, "edges");
            final List<PathData> path = rebuild(List.of(), edges);
            equal(List.of(BRANCH_B, POINT, STEM, POINT, BRANCH_C), nodes(path), "must first enter and reverse on stem");
            check(path.get(1).isOppositeRail(path.get(2)), "native reversal pair precedes return to turnout");
            equal(1L, path.get(1).getDwellTime(), "native stop before returning to C");
            check(Double.isNaN(PathSnapshot.findInvalidJunctionBoundary(path)), "valid branch transfer is not frozen");
        }
    }

    private static void turnbackStop() throws Exception {
        final Rail rail = track(STEM, Angle.E, POINT, Angle.W, true);
        final List<Object> graph = List.of(edge(rail, STEM, POINT), edge(rail, POINT, STEM));
        final Object solved = call("solve", graph, List.of(STEM, POINT, STEM), List.of());
        equal(null, resultField(solved, "failure"), "actual turnback rail is allowed at a waypoint");
        @SuppressWarnings("unchecked") final List<Object> edges = (List<Object>) resultField(solved, "edges");
        final List<PathData> path = rebuild(List.of(), edges);
        check(path.get(0).isOppositeRail(path.get(1)), "native opposite-rail pair");
        equal(1L, path.get(0).getDwellTime(), "MTR automatic mode must stop before reversing");
        final List<PathData> platformPath = rebuild(nativePath(), List.of(edge(platform, B, C), edge(platform, C, B)));
        equal(10000L, platformPath.get(0).getDwellTime(), "scheduled dwell must not be shortened");
    }

    private static ObjectArrayList<PathData> distances(List<PathData> path) {
        final ObjectArrayList<PathData> result = new ObjectArrayList<>(path);
        SidingPathFinder.generatePathDataDistances(result, 0);
        return result;
    }

    private static void cachedJunctionGuard() throws Exception {
        final Turnout t = turnout(false);
        final var bad = distances(List.of(nativeSegment(t.b(), BRANCH_B, POINT, 0, 0, 0),
                nativeSegment(t.c(), POINT, BRANCH_C, 0, 0, 0)));
        equal(bad.get(0).getEndDistance(), PathSnapshot.findInvalidJunctionBoundary(bad), "legacy B -> point -> C");
        // A zero-distance MTR handoff must neither hide the bad pair nor create a false alarm.
        bad.add(1, new PathData(bad.get(0), bad.get(0).getEndDistance(), bad.get(0).getEndDistance()));
        equal(bad.get(0).getEndDistance(), PathSnapshot.findInvalidJunctionBoundary(bad), "zero-length handoff does not hide jump");
        final var legal = distances(List.of(nativeSegment(t.c(), BRANCH_C, POINT, 0, 0, 0),
                nativeSegment(t.stem(), POINT, STEM, 0, 0, 0)));
        legal.add(1, new PathData(legal.get(0), legal.get(0).getEndDistance(), legal.get(0).getEndDistance()));
        check(Double.isNaN(PathSnapshot.findInvalidJunctionBoundary(legal)), "local tangents preserve real curved approach");
        check(Double.isNaN(PathSnapshot.findInvalidJunctionBoundary(distances(nativePath()))), "native platform reversal remains MTR-owned");
    }

    private static void runtimeJunctionGuard() throws Exception {
        final Turnout t = turnout(false);
        final var path = distances(List.of(nativeSegment(t.b(), BRANCH_B, POINT, 0, 0, 0),
                nativeSegment(t.c(), POINT, BRANCH_C, 0, 0, 0)));
        final TestVehicle vehicle = new TestVehicle();
        final TestExtra extra = new TestExtra();
        setField(extra, VehicleExtraData.class, "immutablePath", new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList<>(path));
        setField(vehicle, Vehicle.class, "vehicleExtraData", extra);
        SectionStateManager.beginSimulation(simulator);
        try {
            final double boundary = path.get(0).getEndDistance();
            vehicle.progress = 0; vehicle.speed = 0.022; extra.speedTarget = 0.022;
            final double limit = MovementGate.clampStoppingPoint(vehicle, 1000);
            check(limit < boundary && limit > boundary - 0.001, "brakes on incoming rail even with no BR authorization");
            equal(5.0, MovementGate.clampStoppingPoint(vehicle, 5), "earlier native stop is preserved");
            check(!MovementGate.isInvalidJunctionBlocked(vehicle), "approach remains available for braking");
            vehicle.progress = boundary;
            check(MovementGate.isInvalidJunctionBlocked(vehicle), "native startUp is vetoed at invalid node");
            MovementGate.beforeVehicleSimulation(vehicle);
            equal(0.0, vehicle.speed, "stops before simulation advances");
            equal(0.0, extra.speedTarget, "also clears target speed");
            vehicle.progress = boundary + 2; vehicle.speed = 0.022;
            MovementGate.enforce(vehicle);
            equal(boundary + 2, vehicle.progress, "no teleport back on an already-invalid old path");
            equal(0.0, vehicle.speed, "already past bad node stays stopped");
            equal(vehicle.progress, MovementGate.clampStoppingPoint(vehicle, 1000), "never emits backwards path stop");
            final var repaired = distances(List.of(nativeSegment(t.b(), BRANCH_B, POINT, 0, 0, 0),
                    nativeSegment(t.stem(), POINT, STEM, 0, 0, 0)));
            setField(extra, VehicleExtraData.class, "immutablePath", new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList<>(repaired));
            vehicle.progress = 0; vehicle.speed = 0.022;
            equal(1000.0, MovementGate.clampStoppingPoint(vehicle, 1000), "new valid immutable path clears guard cache");
            check(!MovementGate.isInvalidJunctionBlocked(vehicle), "native startUp veto clears with repaired path");
            MovementGate.beforeVehicleSimulation(vehicle);
            equal(0.022, vehicle.speed, "valid regenerated path is not frozen");
        } finally {
            // Remove only this fixture's thread context, without a simulated world/server tick.
            final var field = SectionStateManager.class.getDeclaredField("CURRENT"); field.setAccessible(true);
            ((ThreadLocal<?>) field.get(null)).remove();
            PathSnapshot.clear(vehicle);
        }
    }

    private static void setField(Object target, Class<?> type, String name, Object value) throws Exception {
        final var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static final class TestVehicle extends Vehicle implements org.mtrbr.mixin.VehicleAccess {
        double progress, speed;
        TestVehicle() { super(new JsonReader(new JsonObject())); }
        public double mtrbr$getRailProgress() { return progress; }
        public void mtrbr$setRailProgress(double value) { progress = value; }
        public double mtrbr$getSpeed() { return speed; }
        public void mtrbr$setSpeed(double value) { speed = value; }
    }

    private static final class TestExtra extends VehicleExtraData implements org.mtrbr.mixin.VehicleExtraDataAccess {
        double speedTarget;
        TestExtra() { super(new JsonReader(new JsonObject())); }
        public void mtrbr$setStoppingPoint(double value) { setStoppingPoint(value); }
        public void mtrbr$setSpeedTarget(double value) { speedTarget = value; }
        public void mtrbr$setPowerLevel(int value) { setPowerLevel(value); }
    }

    private static List<PathData> nativePath() {
        return List.of(nativeSegment(running, A, B, 0, 0, 0), nativeSegment(platform, B, C, 12, 10000, 1),
                nativeSegment(platform, C, B, 12, 10000, 1), nativeSegment(running, B, A, 0, 0, 1));
    }

    private static PathData nativeSegment(Rail rail, Position from, Position to, long id, long dwell, int stop) {
        return new PathData(rail, id, dwell, stop, from, to);
    }

    private static Depot depot(List<PathData> path) {
        final Depot depot = new Depot(TransportMode.TRAIN, simulator);
        depot.getPath().addAll(path);
        simulator.depotIdMap.put(depot.getId(), depot);
        return depot;
    }

    private static JsonObject request(Depot depot, List<Position> nodes) throws Exception {
        final JsonObject request = new JsonObject();
        request.addProperty("depotId", Long.toUnsignedString(depot.getId(), 16));
        request.addProperty("fingerprint", (String) call("fingerprint", depot));
        final JsonArray array = new JsonArray();
        for (final Position position : nodes) {
            final JsonObject node = new JsonObject();
            node.addProperty("x", position.getX()); node.addProperty("y", position.getY()); node.addProperty("z", position.getZ());
            array.add(node);
        }
        request.add("nodes", array);
        return request;
    }

    private static Object edge(Rail rail, Position start, Position end) throws Exception {
        final Class<?> type = Class.forName("org.mtrbr.web.DepotPathEditorService$DirectedRail");
        final Constructor<?> constructor = type.getDeclaredConstructor(Position.class, Position.class, Rail.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(start, end, rail, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> solve(List<Position> nodes) throws Exception {
        final Object graph = call("graph", simulator, List.of());
        final Object solved = call("solve", graph, nodes, nativePath());
        final Method failure = solved.getClass().getDeclaredMethod("failure"); failure.setAccessible(true);
        equal(null, failure.invoke(solved), "existing LINE solver succeeds");
        final Method edges = solved.getClass().getDeclaredMethod("edges"); edges.setAccessible(true);
        return (List<Object>) edges.invoke(solved);
    }

    @SuppressWarnings("unchecked")
    private static List<PathData> rebuild(List<PathData> original, List<Object> edges) throws Exception {
        return (List<PathData>) call("rebuildPath", original, edges);
    }

    private static void verifyPath(List<PathData> path, List<Position> expectedNodes) throws Exception {
        check(path != null, "rebuild succeeds");
        equal(expectedNodes, nodes(path), "directed node sequence");
        for (int i = 1; i < path.size(); i++) {
            final Method sameEnd = WebTopologySnapshot.class.getDeclaredMethod("sameEnd", PathData.class, PathData.class);
            sameEnd.setAccessible(true);
            check((boolean) sameEnd.invoke(null, path.get(i - 1), path.get(i)), "web must not mark segment " + i + " red");
        }
    }

    private static List<Position> nodes(List<PathData> path) {
        final List<Position> result = new ArrayList<>();
        for (final var element : DepotPathEditorService.editorNodesJson(path)) {
            final JsonObject node = element.getAsJsonObject();
            result.add(new Position(node.get("x").getAsLong(), node.get("y").getAsLong(), node.get("z").getAsLong()));
        }
        return result;
    }

    private static JsonObject json(PathData segment) {
        final JsonObject json = new JsonObject(); segment.serializeData(new JsonWriter(json)); return json;
    }

    private static Object call(String name, Object... arguments) throws Exception {
        for (final Method method : DepotPathEditorService.class.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            boolean match = true;
            for (int i = 0; i < arguments.length; i++) if (!method.getParameterTypes()[i].isPrimitive() && !method.getParameterTypes()[i].isInstance(arguments[i])) match = false;
            if (!match) continue;
            method.setAccessible(true);
            try { return method.invoke(null, arguments); }
            catch (InvocationTargetException exception) { throw new AssertionError(name, exception.getCause()); }
        }
        throw new NoSuchMethodException(name);
    }

    private static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS: " + name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }
    private interface Checked { void run() throws Exception; }
}
