package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.loading.FMLPaths;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtrbr.data.SignalBlockSavedData;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executable, dependency-free regression fixtures; run by Gradle check/build. */
public final class TurnbackProjectionRegression {
	private static int passed;
	private static Simulator simulator;

	public static void main(String[] args) throws Exception {
		FMLPaths.loadAbsolutePaths(Path.of(".").toAbsolutePath().normalize());
		simulator = new Simulator("turnback-test", new String[] { "turnback-test" }, Path.of("simulation"), false);
		junction(10, 0);
		junction(20, 0);
		run("platform reversal without canTurnBack, including next dwell occurrence", TurnbackProjectionRegression::platformReverse);
		run("native turnback endpoint remains beyond platform stop", TurnbackProjectionRegression::nativeTurnback);
		run("ordinary dwell and 90/180-degree curves are not reversal", TurnbackProjectionRegression::ordinaryStops);
		run("no reverse at a path-ending platform", TurnbackProjectionRegression::pathEndingPlatform);
		run("next platform reversal is not borrowed by the preceding stop", TurnbackProjectionRegression::nextStop);
		run("full and phase-filtered faces compile the identical fixed Block", TurnbackProjectionRegression::phaseIndependentDefinition);
		run("actual nearer signal wins and later-leg saved boundary is rejected", TurnbackProjectionRegression::nearestSignal);
		run("exact terminal distance keeps inbound and outbound occurrences separate", TurnbackProjectionRegression::terminalEquality);
		run("path refresh/prefix changes preserve physical identity but not occurrence keys", TurnbackProjectionRegression::pathRefresh);
		run("stale occurrence repair preserves manual face mapping and converges", TurnbackProjectionRegression::staleOccurrenceRepair);
		run("missing definitions fail closed even if another occurrence has a saved mapping", TurnbackProjectionRegression::missingDefinition);
		run("inbound resource snapshot excludes reverse movements and preserves conflicts", TurnbackProjectionRegression::junctionProtection);
		run("non-debug diagnostics are visible once per snapshot/stop", TurnbackProjectionRegression::diagnostics);
		System.out.println("Turnback projection regression: " + passed + " cases passed.");
		LifecycleRegression.run(simulator);
	}

	private static void platformReverse() throws Exception {
		final PathSnapshot path = shuttle("platform");
		final PathSnapshot.TurnbackWindow window = path.getNextTurnbackWindow(0);
		check(window.requiresTurnback(), "MTR opposite rail must be recognized without capability flag");
		equal(20.0, window.stopDistance(), "platform stop");
		equal(20.0, window.endDistance(), "first reverse boundary");
		equal(20.0, path.getNextTerminalNode(0).distance(), "Block shares stop reversal");
		equal(40.0, path.getNextTerminalNode(20).distance(), "second end of shuttle");
		equal(40.0, path.getNextTurnbackWindow(30).endDistance(), "second-end turnback window");
	}

	private static void nativeTurnback() throws Exception {
		final PathSnapshot path = new Fixture().add("platform", 0, 0, 10, 0, true, false)
				.add("turnback", 10, 0, 20, 0, false, true).add("turnback", 20, 0, 10, 0, false, true)
				.add("platform", 10, 0, 0, 0, true, false).build("native");
		final PathSnapshot.TurnbackWindow window = path.getNextTurnbackWindow(0);
		check(window.requiresTurnback(), "native reverse");
		equal(10.0, window.stopDistance(), "do not extend scheduled stop");
		equal(20.0, window.endDistance(), "do not truncate native turnback rail at stop");
		equal(window.endDistance(), path.getNextTerminalNode(0).distance(), "shared native boundary");
	}

	private static void ordinaryStops() throws Exception {
		final PathSnapshot path = new Fixture().add("platform", 0, 0, 10, 0, true, true)
				.add("curve1", 10, 0, 10, 10, false, true).add("curve2", 10, 10, 0, 10, false, false)
				.build("curve");
		check(!path.getNextTurnbackWindow(0).requiresTurnback(), "heading changes/capability cannot invent reversal");
		equal(30.0, path.getNextTerminalNode(0).distance(), "curve remains continuous");
		equal(10.0, path.getNextTurnbackWindow(0).stopDistance(), "ordinary station remains a stop");
		final PathSnapshot straight = new Fixture().add("platform", 0, 0, 10, 0, true, false)
				.add("running", 10, 0, 20, 0, false, false).build("straight");
		final var entry = face("entry", 0, 2, 0);
		final var next = face("next", 1, 15, 0);
		equal(next.key(), straight.getNextProtectionBoundary(entry, List.of(entry, next)).face().key(), "ordinary stop is not a Block boundary");
	}

	private static void pathEndingPlatform() throws Exception {
		final PathSnapshot path = new Fixture().add("platform", 0, 0, 10, 0, true, true).build("path-end");
		check(!path.getNextTurnbackWindow(0).requiresTurnback(), "terminal alone is not reverse");
		check(!path.getNextTerminalNode(0).turnback(), "no synthetic native operation");
		check(!path.getNextTurnbackWindow(10).requiresTurnback(), "completed stop is not reselected");
		check(!new Fixture().build("empty").getNextTurnbackWindow(0).requiresTurnback(), "empty path");
	}

	private static void nextStop() throws Exception {
		final PathSnapshot path = new Fixture().add("first", 0, 0, 10, 0, true, false)
				.add("second", 10, 0, 20, 0, true, false).add("second", 20, 0, 10, 0, false, false)
				.add("first", 10, 0, 0, 0, true, false).build("two-stops");
		check(!path.getNextTurnbackWindow(0).requiresTurnback(), "must not borrow next station reversal");
		check(path.getNextTurnbackWindow(10).requiresTurnback(), "next station does reverse");
		equal(20.0, path.getNextTerminalNode(0).distance(), "physical boundary still recognized");
	}

	private static void phaseIndependentDefinition() throws Exception {
		final PathSnapshot path = shuttle("full-vs-phase");
		final var entry = face("entry", 0, 2, 0);
		final var laterLeg = face("later", 4, 45, 0);
		final var generated = RouteProjection.define(simulator, path, List.of(entry, laterLeg), entry);
		final var runtime = RouteProjection.build(simulator, path, List.of(entry), entry, saved(path, entry, generated), 7);
		equal(RouteProjection.Result.READY, runtime.result(), "runtime must find generated definition");
		equal(generated.blockDefinitionId(), runtime.blockDefinitionId(), "same canonical ID");
		equal(List.of(0, 1), runtime.traversals().stream().map(PathSnapshot.PathTraversal::index).toList(), "exclude reverse leg");
		equal(20.0, runtime.boundaryDistance(), "no full-path terminal fallback");
	}

	private static void nearestSignal() throws Exception {
		final PathSnapshot path = shuttle("near-face");
		final var entry = face("entry", 0, 2, 0);
		final var near = face("near", 1, 15, 0);
		final var later = face("later", 4, 45, 0);
		final var faces = List.of(later, near, entry);
		equal(near.key(), path.getNextProtectionBoundary(entry, faces).face().key(), "nearest face before reverse");
		equal(null, path.getProtectionBoundary(entry, faces, later.faceId()), "cannot resolve later direction leg");
		equal(null, path.getProtectionBoundary(entry, faces, "terminal:0,-60,0:180000"), "cannot resolve wrong terminal");
	}

	private static void terminalEquality() throws Exception {
		final PathSnapshot path = shuttle("equality");
		final var inbound = face("end", 1, 20, 0);
		final var outbound = face("end-reverse", 2, 20, 180);
		equal(20.0, path.getNextProtectionBoundary(inbound, List.of(inbound, outbound)).distance(), "inbound stays at its own terminal");
		equal(40.0, path.getNextProtectionBoundary(outbound, List.of(inbound, outbound)).distance(), "outbound gets next direction segment");
		final var empty = RouteProjection.build(simulator, path, List.of(inbound), inbound, emptySaved(), 1);
		equal(RouteProjection.Result.EMPTY_PATH_SEGMENT, empty.result(), "zero length is not authorization");
	}

	private static void pathRefresh() throws Exception {
		final PathSnapshot original = shuttle("before-refresh");
		final PathSnapshot rebuilt = new Fixture().add("prefix", -10, 0, 0, 0, false, false)
				.add("approach", 0, 0, 10, 0, false, false).add("platform", 10, 0, 20, 0, true, false)
				.add("platform", 20, 0, 10, 0, true, false).add("approach", 10, 0, 0, 0, true, false)
				.add("approach", 0, 0, 10, 0, false, false).build("after-refresh");
		final var entry = face("entry", 0, 2, 0);
		final var shifted = face("entry", 1, 12, 0);
		final var first = RouteProjection.define(simulator, original, List.of(entry), entry);
		final var second = RouteProjection.define(simulator, rebuilt, List.of(shifted), shifted);
		equal(first.blockDefinitionId(), second.blockDefinitionId(), "prefix-independent physical identity");
		check(!SignalBlockSavedData.occurrenceKey(original.getFingerprint(), entry.key()).equals(
				SignalBlockSavedData.occurrenceKey(rebuilt.getFingerprint(), shifted.key())), "refresh needs a new occurrence key");
		final var stored = new SignalBlockSavedData();
		stored.addGeneratedOccurrenceBlocks(Map.of(SignalBlockSavedData.occurrenceKey(original.getFingerprint(), entry.key()),
				new RouteRequestManager.GeneratedProtection(first.blockDefinitionId(), first.sectionIds(), first.boundary().id())));
		equal(1, stored.addGeneratedOccurrenceBlocks(Map.of(SignalBlockSavedData.occurrenceKey(rebuilt.getFingerprint(), shifted.key()),
				new RouteRequestManager.GeneratedProtection(second.blockDefinitionId(), second.sectionIds(), second.boundary().id()))), "existing repair handles refreshed occurrence");
		final var runtime = RouteProjection.build(simulator, rebuilt, List.of(shifted), shifted, saved(rebuilt, shifted, second), 8);
		equal(RouteProjection.Result.READY, runtime.result(), "rebuilt path definition is usable");
	}

	private static void staleOccurrenceRepair() throws Exception {
		final PathSnapshot path = shuttle("stale-occurrence");
		final var entry = face("entry", 0, 2, 0);
		final var definition = RouteProjection.define(simulator, path, List.of(entry), entry);
		final String staleId = "entry->terminal:999,-60,0:0|dir=0|traversals=stale|junctions=";
		final var stored = new SignalBlockSavedData();
		stored.setBlock(entry.faceId(), staleId, List.of("approach", "platform", "reverse-only"));
		stored.setOccurrenceBlock(path.getFingerprint(), entry.key(), staleId);
		equal(staleId, stored.getOccurrenceBlockId(path.getFingerprint(), entry.key()), "fixture contains stale occurrence");
		final var generated = Map.of(SignalBlockSavedData.occurrenceKey(path.getFingerprint(), entry.key()),
				new RouteRequestManager.GeneratedProtection(definition.blockDefinitionId(), definition.sectionIds(), definition.boundary().id()));
		equal(1, stored.addGeneratedOccurrenceBlocks(generated), "same occurrence's stale definition is repaired");
		equal(definition.blockDefinitionId(), stored.getOccurrenceBlockId(path.getFingerprint(), entry.key()), "occurrence uses corrected boundary");
		equal(staleId, stored.getBlockId(entry.faceId()), "operator face mapping remains untouched");
		equal(0, stored.addGeneratedOccurrenceBlocks(generated), "repair converges rather than regenerating every tick");
		final var snapshot = new SignalBlockSavedData.Snapshot(Map.of(entry.faceId(), stored.getBlockId(entry.faceId())),
				Map.of(SignalBlockSavedData.occurrenceKey(path.getFingerprint(), entry.key()), stored.getOccurrenceBlockId(path.getFingerprint(), entry.key())),
				Map.of(definition.blockDefinitionId(), stored.getRailIdsForBlock(definition.blockDefinitionId())), Map.of());
		equal(RouteProjection.Result.READY, RouteProjection.build(simulator, path, List.of(entry), entry, snapshot, 9).result(), "real repaired storage feeds runtime projection");
	}
	private static void missingDefinition() throws Exception {
		final PathSnapshot path = shuttle("missing");
		final var entry = face("entry", 0, 2, 0);
		final var wrongMapping = new SignalBlockSavedData.Snapshot(Map.of(),
				Map.of(SignalBlockSavedData.occurrenceKey(path.getFingerprint(), entry.key()), "old-terminal"),
				Map.of("old-terminal", List.of("approach", "platform")), Map.of());
		equal(RouteProjection.Result.BLOCK_DEFINITION_MISSING,
				RouteProjection.build(simulator, path, List.of(entry), entry, wrongMapping, 1).result(), "do not borrow old saved boundary");
	}

	private static void junctionProtection() throws Exception {
		final PathSnapshot path = shuttle("junctions");
		final var entry = face("entry", 0, 2, 0);
		final var definition = RouteProjection.define(simulator, path, List.of(entry), entry);
		check(!definition.junctionMovementIds().isEmpty(), "fixture must exercise real JunctionStateManager");
		check(definition.junctionMovementIds().stream().noneMatch(key -> key.matches(".*\\|path=[2-9][0-9]*$")), "no reverse occurrence locks");
		check(definition.junctionMovementIds().stream().anyMatch(key -> key.contains("|through=platform|out=<exit>")), "inbound movement ends before reverse");
		final var outbound = face("reverse", 2, 20, 180);
		final var departure = RouteProjection.define(simulator, path, List.of(outbound), outbound);
		JunctionStateManager.registerOwner(simulator, "inbound", 1);
		check(JunctionStateManager.reserve(simulator, definition.junctionMovementIds(), "inbound"), "reserve inbound resources");
		check(JunctionStateManager.conflicts(simulator, departure.junctionMovementIds(), "other-request"), "manual/auto request must still conflict on shared physical junction");
		JunctionStateManager.release(simulator, definition.junctionMovementIds(), "wrong-owner");
		check(JunctionStateManager.conflicts(simulator, departure.junctionMovementIds(), "other-request"), "wrong owner cannot release resources");
		JunctionStateManager.release(simulator, definition.junctionMovementIds(), "inbound");
		check(!JunctionStateManager.conflicts(simulator, departure.junctionMovementIds(), "other-request"), "correct owner can release fixture");
	}

	private static void diagnostics() throws Exception {
		final PathSnapshot path = shuttle("diagnostic-once");
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		final PrintStream original = System.out;
		try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
			System.setOut(capture);
			for (int i = 0; i < 20; i++) path.getNextTurnbackWindow(i * 0.1);
		} finally { System.setOut(original); }
		final String text = output.toString(StandardCharsets.UTF_8);
		equal(1L, text.lines().filter(line -> line.contains("MTRBR-TURNBACK-OCCURRENCE")).count(), "diagnostic must not be filtered or repeated per tick");
		check(text.contains("requiresTurnback=true") && text.contains("reverseAfterTraversal=2"), "actionable reverse evidence");
	}

	private static PathSnapshot shuttle(String fingerprint) throws Exception {
		return new Fixture().add("approach", 0, 0, 10, 0, false, false).add("platform", 10, 0, 20, 0, true, false)
				.add("platform", 20, 0, 10, 0, true, false).add("approach", 10, 0, 0, 0, true, false)
				.add("approach", 0, 0, 10, 0, false, false).build(fingerprint);
	}

	private static PathSnapshot.FaceTraversal face(String id, int index, double distance, double angle) {
		return new PathSnapshot.FaceTraversal(id, index, 0, new SignalFace(id, BlockPos.ZERO, BlockPos.ZERO, false, (float) angle), distance, angle, (int) (angle * 1000));
	}

	private static SignalBlockSavedData.Snapshot saved(PathSnapshot path, PathSnapshot.FaceTraversal entry, RouteProjection.Definition definition) {
		return new SignalBlockSavedData.Snapshot(Map.of(entry.faceId(), definition.blockDefinitionId()),
				Map.of(SignalBlockSavedData.occurrenceKey(path.getFingerprint(), entry.key()), definition.blockDefinitionId()),
				Map.of(definition.blockDefinitionId(), definition.sectionIds()), Map.of());
	}

	private static SignalBlockSavedData.Snapshot emptySaved() { return new SignalBlockSavedData.Snapshot(Map.of(), Map.of(), Map.of(), Map.of()); }

	private static void junction(int x, int z) {
		final var branches = new Object2ObjectOpenHashMap<Position, Rail>();
		for (int i = 0; i < 3; i++) branches.put(new Position(x + i + 1, -60, z + 1), null);
		simulator.positionsToRail.put(new Position(x, -60, z), branches);
	}

	private static void run(String name, CheckedTest test) throws Exception {
		test.run(); passed++; System.out.println("PASS: " + name);
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static void equal(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
	}
	@FunctionalInterface private interface CheckedTest { void run() throws Exception; }

	private static final class Fixture {
		private final List<PathSnapshot.PathSection> sections = new ArrayList<>();
		private double distance;
		private int stop;
		Fixture add(String id, int x1, int z1, int x2, int z2, boolean platform, boolean canTurnBack) {
			final BlockPos from = new BlockPos(x1, -60, z1), to = new BlockPos(x2, -60, z2);
			final double end = distance + Math.sqrt(from.distSqr(to));
			final double angle = (Math.toDegrees(Math.atan2(z2 - z1, x2 - x1)) + 360) % 360;
			sections.add(new PathSnapshot.PathSection(id, distance, end, "rail:" + id, from, to, angle, from.compareTo(to) > 0,
					platform ? 1000 : 0, platform, false, platform ? stop++ : stop, canTurnBack));
			distance = end; return this;
		}
		PathSnapshot build(String fingerprint) throws Exception {
			final Constructor<PathSnapshot> constructor = PathSnapshot.class.getDeclaredConstructor(List.class, String.class);
			constructor.setAccessible(true);
			return constructor.newInstance(sections, fingerprint);
		}
	}
}
