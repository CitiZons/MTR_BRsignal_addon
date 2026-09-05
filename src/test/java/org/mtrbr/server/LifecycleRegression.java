package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import org.mtr.core.data.Vehicle;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtrbr.data.SignalBlockSavedData;

import java.lang.reflect.*;
import java.util.*;

/** Exercises the production lifecycle methods, with private simulator state as fixtures. */
final class LifecycleRegression {
	private static Simulator sim;
	private static int passed;
	private static final Class<?> VS = nested(RouteRequestManager.class, "VehicleState");

	static void run(Simulator simulator) throws Exception {
		sim = simulator;
		SectionStateManager.beginSimulation(sim);
		test("short movement authority protects every rail and canonical Block", LifecycleRegression::fullBlock);
		test("occupied invalidation -> owner handover -> stale sweep -> physical clearance", LifecycleRegression::handover);
		test("requestless unmanaged pending leases still clear at tick tail", LifecycleRegression::requestless);
		test("timetable distance reset retains occupied interlocking resources", LifecycleRegression::cycleReset);
		test("real FaceSnapshot recovers terminal Block with no signal ahead", LifecycleRegression::containingRecovery);
		test("shared canonical Block survives a cleared prefix", LifecycleRegression::sharedBlock);
		test("Request-end distance cannot override physical occupancy", LifecycleRegression::requestEnd);
		test("same-train handover rejects another train", LifecycleRegression::wrongTrain);
		test("entered single-line zone preserves owner and release state", LifecycleRegression::zoneHandover);
		test("rollback selection excludes pre-existing reserved and locked resources", LifecycleRegression::rollbackSelection);
		System.out.println("Dispatch lifecycle regression: " + passed + " cases passed.");
	}

	private static void fullBlock() throws Exception {
		var block = block("full", List.of("f1", "f2"), 10);
		check(block.sectionIds().equals(List.of("f1", "f2")), "prefix must protect far rail");
		check(block.endDistance() == 10 && block.savedBlockEndDistance() == 20, "authority must not extend to physical boundary");
		check(ids(block).contains(block.blockId()), "incomplete block still needs canonical identity");
		section("f1"); section("f2");
		occupants("f2").add(99L);
		check(!SectionStateManager.areSectionsAvailable(sim, block.sectionIds(), "new", 1, false), "distant occupancy denies short authority");
		occupants("f2").clear();
	}

	private static void handover() throws Exception {
		var b = block("handover", List.of("h1", "h2"), 10);
		var v = vehicle(b, Set.of("h2")); var old = request(v);
		hold(b, old);
		call("invalidateAuthorization", sim, v, ReleaseReason.INVALID, RequestState.INVALID);
		check(get(v, "authorization") == null && !map(v, "pendingReleaseBlocks").isEmpty(), "retire authority, retain protection");
		var next = new RouteRequest(old.getVehicleId(), "refreshed", 2, 2, b.sectionIds(), List.of(), List.of());
		call("adoptRetainedResources", sim, v, old, next); set(v, "request", next);
		sweep(v);
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(b), next.getRequestId()), "new owner survives stale sweep");
		check(!SectionStateManager.areSectionsAvailable(sim, b.sectionIds(), "third", 999, false), "another train remains denied");
		check(JunctionStateManager.areResourcesReservedAndLockedBy(sim, b.junctionMovementIds(), next.getRequestId()), "junction transferred without loss");
		SectionStateManager.releaseSections(sim, b.sectionIds(), old.getRequestId());
		check(SectionStateManager.getSections(sim).get("h2").lockedBy.contains(next.getRequestId()), "old owner cannot unlock new lease");
		call("releasePendingReleaseOccupancy", sim, v);
		check(SectionStateManager.isBlockConflicted(sim, b.blockId(), "third"), "still occupied after request refresh");
		set(v, "sections", Set.of()); call("releasePendingReleaseOccupancy", sim, v);
		check(SectionStateManager.areBlocksAvailable(sim, ids(b), "third"), "correct owner released after clearance");
		check(SectionStateManager.areSectionsAvailable(sim, b.sectionIds(), "third", 999, false), "sections released together");
		check(!JunctionStateManager.conflicts(sim, b.junctionMovementIds(), "third"), "junction released together");
	}

	private static void requestless() throws Exception {
		var b = block("requestless", List.of("r1"), 10); var v = vehicle(b, Set.of("r1")); hold(b, request(v));
		call("invalidateAuthorization", sim, v, ReleaseReason.INVALID, RequestState.INVALID);
		set(v, "request", null); set(v, "managed", false);
		sweep(v);
		check(SectionStateManager.isBlockConflicted(sim, b.blockId(), "other"), "pending retains its own owner without request");
		set(v, "sections", Set.of()); set(v, "observed", true);
		Object state = construct(nested(RouteRequestManager.class, "State"));
		map(state, "vehicles").put(((Vehicle)get(v, "vehicle")).getId(), v);
		map(RouteRequestManager.class, "STATES").put(sim, state);
		RouteRequestManager.finishSimulationTick(sim);
		check(map(v, "pendingReleaseBlocks").isEmpty() && map(v, "pendingReleaseSections").isEmpty(), "unmanaged tick must process pending cleanup");
		check(SectionStateManager.areBlocksAvailable(sim, ids(b), "other"), "requestless exact-owner release");
		map(RouteRequestManager.class, "STATES").remove(sim);
	}

	private static void cycleReset() throws Exception {
		var b = block("cycle", List.of("c1", "c2"), 10); var v = vehicle(b, Set.of("c1")); hold(b, request(v));
		set(v, "head", 0.0); set(v, "tail", -5.0); set(v, "fixedGateBoundary", 200.0); set(v, "fixedGateBoundarySignature", "old-cycle");
		call("resetRouteCycle", sim, v);
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(b), request(v).getRequestId()), "distance reset cannot release occupied block");
		check(JunctionStateManager.conflicts(sim, b.junctionMovementIds(), "other"), "junction still protected");
		check(request(v).getState() == RequestState.INVALID && get(v, "authorization") == null, "old movement authority invalid");
		check(((String)get(v, "fixedGateBoundarySignature")).isEmpty(), "old gate identity discarded");
		set(v, "sections", Set.of()); call("releasePendingReleaseOccupancy", sim, v);
	}

	private static void containingRecovery() throws Exception {
		var path = path("recovery", List.of("p1", "p2", "p3"));
		var entry = new SignalFace("entry", BlockPos.ZERO, new BlockPos(0, -60, 0), false, 0);
		var snapshot = new ServerAspectManager.FaceSnapshot(Map.of(entry.id(), entry), 781);
		set(ServerAspectManager.class, "FACE_SNAPSHOTS", Map.of(sim.dimension, snapshot));
		var faces = path.getFaceTraversals(sim.dimension, snapshot);
		check(faces.size() == 1 && faces.get(0).pathTraversalIndex() == 0, "real node-to-occurrence projection");
		var b = block("recovery", List.of("p1", "p2", "p3"), 30); var v = vehicle(b, Set.of("p2"));
		set(v, "path", path); set(v, "head", 15.0); set(v, "controlDistance", 15.0);
		var range = call("findControlRange", sim, v);
		check(range != null && ((Double)recordValue(range, "controlDistance")) == 15.0, "recover containing block from current head, no future signal");
		check(call("containingBlockEntry", path, faces, 30.0) == null, "terminal equality must not borrow preceding block");
		var definition = RouteProjection.define(sim, path, faces, faces.get(0));
		set(SignalBlockSavedData.class, "SNAPSHOTS", Map.of(sim.dimension, new SignalBlockSavedData.Snapshot(
				Map.of(entry.id(), definition.blockDefinitionId()), Map.of(SignalBlockSavedData.occurrenceKey(path.getFingerprint(), faces.get(0).key()), definition.blockDefinitionId()),
				Map.of(definition.blockDefinitionId(), definition.sectionIds()), Map.of())));
		for (String id : b.sectionIds()) section(id);
		var clearance = call("clearancePrefix", sim, v, 15.0, 30.0);
		check(!((List<?>)recordValue(clearance, "blockAuthorizations")).isEmpty(), "passed entry face must still produce validated authorization");
		occupants("p3").add(99L);
		clearance = call("clearancePrefix", sim, v, 15.0, 20.0);
		check(((List<?>)recordValue(clearance, "blockAuthorizations")).isEmpty(), "short recovery denied by occupancy beyond movement limit");
		occupants("p3").clear();
		set(SignalBlockSavedData.class, "SNAPSHOTS", Map.of());
		clearance = call("clearancePrefix", sim, v, 15.0, 30.0);
		check(((List<?>)recordValue(clearance, "blockAuthorizations")).isEmpty(), "containing block cannot bypass missing mapping");
		set(ServerAspectManager.class, "FACE_SNAPSHOTS", Map.of());
	}

	private static void sharedBlock() throws Exception {
		var first = block("shared", List.of("s1", "s2"), 10); var second = block("shared", List.of("s1", "s2"), 20);
		var v = vehicle(first, Set.of("s2")); hold(first, request(v)); hold(second, request(v));
		set(v, "authorization", new Authorization("shared-auth", request(v).getRequestId(), List.of(first, second), List.of(), 0, 0));
		set(v, "tail", 15.0);
		call("releaseAuthorizationPastHead", sim, v);
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(first), request(v).getRequestId()), "tail passed prefix but whole block remains occupied");
		var releasable = (List<?>)call("releasableBlockLockIds", ids(first), List.of(second), List.of());
		check(!releasable.contains(first.blockId()) && releasable.contains(first.occurrenceId()), "shared canonical ID must outlive individual prefix");
		call("invalidateAuthorization", sim, v, ReleaseReason.INVALID, RequestState.INVALID);
		set(v, "sections", Set.of()); call("releasePendingReleaseOccupancy", sim, v);
		check(SectionStateManager.areBlocksAvailable(sim, List.of(first.blockId()), "other"), "last pending reference releases canonical ID");
	}

	private static void requestEnd() throws Exception {
		var b = block("end", List.of("e1", "e2"), 20); var v = vehicle(b, Set.of("e2")); hold(b, request(v));
		set(v, "endDistance", 20.0); set(v, "head", 25.0); set(v, "tail", 21.0);
		call("updateAuthorizedLifecycle", sim, v);
		check(request(v).getState() == RequestState.ACTIVE, "distance disagreement must not release or mark passed");
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(b), request(v).getRequestId()), "occupied request-end block retained");
		set(v, "sections", Set.of()); call("updateAuthorizedLifecycle", sim, v);
		check(request(v).getState() == RequestState.RELEASED, "confirmed physical clearance completes Request");
		check(SectionStateManager.areBlocksAvailable(sim, ids(b), "other"), "cleared Request releases protection");
	}

	private static void wrongTrain() throws Exception {
		var b = block("identity", List.of("i1"), 10); var v = vehicle(b, Set.of());
		var other = new RouteRequest(request(v).getVehicleId() + 1, "other", 2, 1, List.of(), List.of(), List.of());
		try { call("adoptRetainedResources", sim, v, request(v), other); throw new AssertionError("cross-train transfer allowed"); }
		catch (InvocationTargetException expected) { check(expected.getCause() instanceof IllegalArgumentException, "identity guard"); }
	}

	private static void zoneHandover() throws Exception {
		var zone = new SignalBlockSavedData.SingleLineZoneDefinition("zone", List.of("z1", "z2"), 0, 0);
		var path = path("zone", zone.sectionIds());
		CapacityLeaseManager.getZoneIds(sim, path.getTraversals(), new SignalBlockSavedData.Snapshot(Map.of(), Map.of(), Map.of(), Map.of(zone.zoneId(), zone)));
		check(CapacityLeaseManager.reserveZones(sim, List.of("zone"), "old-zone", 567), "reserve zone");
		check(CapacityLeaseManager.lockZones(sim, List.of("zone"), "old-zone"), "lock zone");
		CapacityLeaseManager.releaseExitedZones(sim, 567, Set.of("z1"));
		CapacityLeaseManager.transferRequestOwner(sim, "old-zone", "new-zone", 567);
		CapacityLeaseManager.releaseUnenteredZones(sim, "new-zone");
		check(!CapacityLeaseManager.areZonesAvailable(sim, List.of("zone"), "third"), "entered flag survives ownership handover");
		check(CapacityLeaseManager.areZonesAvailable(sim, List.of("zone"), "new-zone"), "new owner continues same zone");
		CapacityLeaseManager.releaseExitedZones(sim, 567, Set.of());
		check(CapacityLeaseManager.areZonesAvailable(sim, List.of("zone"), "third"), "zone releases after first entry and whole train clearance");
	}

	private static void rollbackSelection() {
		SectionStateManager.reserveBlocks(sim, List.of("prior-block"), "rollback-owner");
		JunctionStateManager.reserve(sim, List.of("prior-junction"), "rollback-owner");
		check(SectionStateManager.unownedBlocks(sim, List.of("prior-block", "new-block"), "rollback-owner").equals(List.of("new-block")), "do not roll back old reserved block");
		check(JunctionStateManager.unownedResources(sim, List.of("prior-junction", "new-junction"), "rollback-owner").equals(List.of("new-junction")), "do not roll back old junction");
		SectionStateManager.lockBlocks(sim, List.of("prior-block"), "rollback-owner");
		check(SectionStateManager.unownedBlocks(sim, List.of("prior-block"), "rollback-owner").isEmpty(), "do not roll back old locked block");
		SectionStateManager.releaseBlocks(sim, List.of("prior-block"), "rollback-owner");
		JunctionStateManager.release(sim, List.of("prior-junction"), "rollback-owner");
	}

	private static Authorization.BlockAuthorization block(String name, List<String> rails, double end) throws Exception {
		var p = path(name, rails);
		return new Authorization.BlockAuthorization(name, 0, 0, end, List.of(rails.get(0)), p.getTraversals(), List.of(), end >= p.getTotalDistance(),
				"terminal", p.getTotalDistance(), null, null, name, rails, List.of("junction-node|" + name));
	}
	private static PathSnapshot path(String name, List<String> rails) throws Exception {
		List<PathSnapshot.PathSection> sections = new ArrayList<>();
		for (int i = 0; i < rails.size(); i++) sections.add(new PathSnapshot.PathSection(rails.get(i), i * 10, (i + 1) * 10, "rail:" + rails.get(i),
				new BlockPos(i * 10, -60, 0), new BlockPos((i + 1) * 10, -60, 0), 0, false, 0, false, false, 0, false));
		var c = PathSnapshot.class.getDeclaredConstructor(List.class, String.class); c.setAccessible(true); return c.newInstance(sections, name);
	}
	private static Object vehicle(Authorization.BlockAuthorization b, Set<String> occupied) throws Exception {
		Object v = construct(VS); Vehicle train = new TestVehicle();
		var r = new RouteRequest(train.getId(), b.pathFingerprint(), 1, 1, b.sectionIds(), b.traversals(), List.of());
		for (var stage : List.of(RequestState.APPROACHING, RequestState.REQUESTED, RequestState.CHECKING, RequestState.WAITING, RequestState.AUTHORIZED)) r.transitionTo(stage, "fixture");
		set(v, "vehicle", train); set(v, "request", r); set(v, "path", path(b.pathFingerprint(), b.sectionIds()));
		set(v, "head", 15.0); set(v, "tail", 5.0); set(v, "sections", occupied); set(v, "managed", true);
		set(v, "authorization", new Authorization("auth-" + b.blockId(), r.getRequestId(), List.of(b), List.of(), 0, 0));
		return v;
	}
	private static void hold(Authorization.BlockAuthorization b, RouteRequest r) throws Exception {
		for (String id : b.sectionIds()) section(id);
		check(SectionStateManager.reserveSections(sim, b.sectionIds(), r.getRequestId(), r.getVehicleId(), false), "reserve sections");
		check(SectionStateManager.lockSections(sim, b.sectionIds(), r.getRequestId()), "lock sections");
		check(SectionStateManager.reserveBlocks(sim, ids(b), r.getRequestId()), "reserve blocks");
		check(SectionStateManager.lockBlocks(sim, ids(b), r.getRequestId()), "lock blocks");
		JunctionStateManager.registerOwner(sim, r.getRequestId(), r.getVehicleId());
		check(JunctionStateManager.reserve(sim, b.junctionMovementIds(), r.getRequestId()), "reserve junction");
		check(JunctionStateManager.lock(sim, b.junctionMovementIds(), r.getRequestId()), "lock junction");
	}
	private static void sweep(Object v) throws Exception {
		Map<String, Set<String>> sections = new HashMap<>(), blocks = new HashMap<>(), junctions = new HashMap<>();
		call("retainPendingOwners", v, sections, blocks, junctions);
		SectionStateManager.releaseStaleReservations(sim, Set.of(), sections, blocks);
		JunctionStateManager.releaseStale(sim, Set.of(), junctions);
	}
	private static void section(String id) throws Exception {
		Object state = map(SectionStateManager.class, "STATES").get(sim);
		if (!map(state, "sections").containsKey(id)) {
			var c = nested(SectionStateManager.class, "SectionRecord").getDeclaredConstructor(String.class); c.setAccessible(true);
			Object section = c.newInstance(id); set(section, "exists", true); map(state, "sections").put(id, section);
		}
	}
	@SuppressWarnings("unchecked") private static Set<Long> occupants(String id) throws Exception {
		return (Set<Long>)get(map(map(SectionStateManager.class, "STATES").get(sim), "sections").get(id), "occupiedBy");
	}
	@SuppressWarnings("unchecked") private static List<String> ids(Authorization.BlockAuthorization b) throws Exception { return (List<String>)call("blockLockIds", List.of(b)); }
	private static RouteRequest request(Object v) throws Exception { return (RouteRequest)get(v, "request"); }
	private static Object call(String name, Object... args) throws Exception {
		for (Method m : RouteRequestManager.class.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) {
			m.setAccessible(true); return m.invoke(null, args);
		}
		throw new NoSuchMethodException(name);
	}
	private static Object recordValue(Object record, String name) throws Exception { var m = record.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(record); }
	private static Class<?> nested(Class<?> type, String name) { return Arrays.stream(type.getDeclaredClasses()).filter(c -> c.getSimpleName().equals(name)).findFirst().orElseThrow(); }
	private static Object construct(Class<?> type) throws Exception { var c = type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
	private static Field field(Object target, String name) throws Exception { var f = (target instanceof Class<?> c ? c : target.getClass()).getDeclaredField(name); f.setAccessible(true); return f; }
	private static Object get(Object target, String name) throws Exception { return field(target, name).get(target instanceof Class<?> ? null : target); }
	private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target instanceof Class<?> ? null : target, value); }
	@SuppressWarnings("unchecked") private static Map<Object, Object> map(Object target, String name) throws Exception { return (Map<Object, Object>)get(target, name); }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static void test(String name, Test test) throws Exception { test.run(); passed++; System.out.println("PASS: " + name); }
	/** Supplies the accessors normally woven into Vehicle by the game runtime. */
	private static final class TestVehicle extends Vehicle implements org.mtrbr.mixin.VehicleAccess {
		private double progress;
		private double testSpeed;
		TestVehicle() { super(new JsonReader(new JsonObject())); }
		public double mtrbr$getRailProgress() { return progress; }
		public void mtrbr$setRailProgress(double value) { progress = value; }
		public double mtrbr$getSpeed() { return testSpeed; }
		public void mtrbr$setSpeed(double value) { testSpeed = value; }
	}
	@FunctionalInterface private interface Test { void run() throws Exception; }
}
