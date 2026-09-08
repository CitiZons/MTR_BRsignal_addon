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
		test("shunt requests one Block per passed node, respects occupancy and keeps upstream red propagation", LifecycleRegression::shunt);
		test("shunt names and binding save/load/unbind lifecycle", LifecycleRegression::shuntBindings);
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

	private static void shunt() throws Exception {
		final var rails = List.of("shunt0", "shunt1", "shunt2", "shunt3", "shunt4", "shunt5");
		final var path = path("shunt", rails);
		final Map<String, SignalFace> signals = new LinkedHashMap<>();
		for (int i = 1; i < 6; i++) signals.put("shunt-face-" + i, new SignalFace("shunt-face-" + i,
				new BlockPos(i * 10, -59, 0), new BlockPos(i * 10, -60, 0), false, 180));
		final var topology = new ServerAspectManager.FaceSnapshot(signals, 981);
		set(ServerAspectManager.class, "FACE_SNAPSHOTS", Map.of(sim.dimension, topology));
		final var faces = path.getFaceTraversals(sim.dimension, topology).stream().filter(PathSnapshot::isDirectionMatched).toList();
		check(faces.size() == 5, "fixture needs all five directed faces");
		final var shunt = signals.get("shunt-face-3").signalPos();
		final var destination = new BlockPos(50, -60, 0);
		final String compound = "route=2 || path=1 || shunt=yard";
		final var binding = new org.mtrbr.data.RouteBinding(destination, compound);
		ShuntSignalPolicy.publish(sim.dimension, Map.of(shunt, List.of(binding)), Set.of(shunt));
		check(ShuntSignalPolicy.route(sim.dimension, path, faces.get(2)).equals(compound), "target beyond first block retains shunt and indicator content");
		final var mixedFaces = new ArrayList<>(faces);
		mixedFaces.add(new PathSnapshot.FaceTraversal("reverse-face", 3, 0,
				new SignalFace("reverse-face", new BlockPos(35, -59, 0), new BlockPos(35, -60, 0), true, 0), 35, 180, 0));
		check(ShuntSignalPolicy.exitAtHead(sim.dimension, path, mixedFaces, 35) == null, "opposite-facing signal is not a shunt exit");
		check(ShuntSignalPolicy.exitAtHead(sim.dimension, path, mixedFaces, 40).key().sameIdentity(faces.get(3).key()), "exit uses next same-direction signal");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 25) == 40, "one block before entry");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 30) == 40, "entry must be crossed before the next request");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 30.01) == 50, "crossed entry permits one new Block immediately");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 35) == 50, "moving train can request one Block beyond the next signal");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 40) == 50, "at the main node the one-Block cap remains");
		check(Double.isInfinite(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 40.01)), "crossing ordinary main restores normal lookahead");
		final var secondShunt = faces.get(3).face().signalPos();
		ShuntSignalPolicy.publish(sim.dimension, Map.of(shunt, List.of(binding), secondShunt, List.of(binding)), Set.of(shunt, secondShunt));
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 35) == 50, "consecutive shunts cannot reserve two future Blocks");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 40) == 50, "second shunt must also be passed");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 40.01) == 60, "passing second shunt unlocks exactly one further Block");
		check(ShuntSignalPolicy.boundary(sim.dimension, path, mixedFaces, 35) == 50, "opposite-facing main cannot clear shunt restriction");
		ShuntSignalPolicy.publish(sim.dimension, Map.of(shunt, List.of(binding)), Set.of(shunt));
		check(Double.isInfinite(ShuntSignalPolicy.boundary("another-dimension", path, faces, 15)), "dimension isolation");
		final Map<String,String> faceBlocks = new HashMap<>(), occurrences = new HashMap<>();
		final Map<String,List<String>> blockRails = new HashMap<>();
		for (var face : faces) {
			final var definition = RouteProjection.define(sim, path, faces, face);
			faceBlocks.put(face.faceId(), definition.blockDefinitionId());
			occurrences.put(SignalBlockSavedData.occurrenceKey(path.getFingerprint(), face.key()), definition.blockDefinitionId());
			blockRails.put(definition.blockDefinitionId(), definition.sectionIds());
		}
		set(SignalBlockSavedData.class, "SNAPSHOTS", Map.of(sim.dimension, new SignalBlockSavedData.Snapshot(faceBlocks, occurrences, blockRails, Map.of())));
		for (String rail : rails) section(rail);
		final Object vehicle = vehicle(block("shunt", rails, 50), Set.of());
		set(vehicle, "authorization", null);
		set(vehicle, "head", 25.0);
		set(vehicle, "endDistance", 50.0);
		set(vehicle, "controlDistance", 30.0);
		set(vehicle, "authorizationLookaheadEndDistance", 50.0);
		Object clearance = call("clearancePrefix", sim, vehicle, 30.0, 50.0);
		check(((List<?>)recordValue(clearance, "blockAuthorizations")).size() == 1, "production clearance grants exactly one Block");
		check((double)recordValue(clearance, "endDistance") == 40, "production authorization stops at next face");
		check((double)call("authorizationBoundary", sim, vehicle, 40.0) == 40, "extension start cannot move shunt limit");
		call("refreshAuthorizationLookahead", sim, vehicle);
		check((double)get(vehicle, "authorizationLookaheadEndDistance") == 40, "moving preview cannot open second block");
		occupants("shunt4").add(998L);
		clearance = call("clearancePrefix", sim, vehicle, 30.0, 50.0);
		check(((List<?>)recordValue(clearance, "blockAuthorizations")).size() == 1, "second-block occupation does not deny first-block permission");
		occupants("shunt3").add(999L);
		clearance = call("clearancePrefix", sim, vehicle, 30.0, 50.0);
		check(((List<?>)recordValue(clearance, "blockAuthorizations")).isEmpty(), "first-block occupation denies shunt permission");
		occupants("shunt3").clear(); occupants("shunt4").clear();
		final var auth = new RouteRequestManager.AuthorizedPath(1, "TEST", path, path.getTraversals(), List.of(), 0, 60,
				new ArrayList<>(faceBlocks.values()), faces.stream().map(PathSnapshot.FaceTraversal::key).toList(), "test:auth", 1);
		final var resolver = ServerAspectManager.class.getDeclaredMethod("resolveAspect", String.class, ServerAspectManager.FaceSnapshot.class,
				RouteRequestManager.AuthorizedPath.class, PathSnapshot.FaceTraversal.class, Set.class);
		resolver.setAccessible(true);
		check(resolver.invoke(null, sim.dimension, topology, auth, faces.get(2), new HashSet<>()) == ServerAspect.RED, "shunt main stays red");
		check(resolver.invoke(null, sim.dimension, topology, auth, faces.get(1), new HashSet<>()) == ServerAspect.YELLOW, "previous main becomes yellow");
		check(resolver.invoke(null, sim.dimension, topology, auth, faces.get(0), new HashSet<>()) == ServerAspect.DOUBLE_YELLOW, "second previous main becomes double yellow");
		final var entryClearance = call("clearancePrefix", sim, vehicle, 30.0, 50.0);
		final var entryBlock = (Authorization.BlockAuthorization)((List<?>)recordValue(entryClearance, "blockAuthorizations")).get(0);
		hold(entryBlock, request(vehicle));
		final var entryAuth = new Authorization("shunt-moving-auth", request(vehicle).getRequestId(), List.of(entryBlock), List.of(), 0, 10);
		set(vehicle, "authorization", entryAuth);
		final var movingState = construct(nested(RouteRequestManager.class, "State"));
		final TestVehicle movingTrain = (TestVehicle)get(vehicle, "vehicle");
		map(movingState, "vehicles").put(movingTrain.getId(), vehicle);
		final Object previousState = map(RouteRequestManager.class, "STATES").put(sim, movingState);
		try {
			for (double head : new double[] {25, 29.99, 30, 35, 39.99}) {
				set(vehicle, "head", head);
				set(vehicle, "activityAuthorization", new RouteRequestManager.ActivityAuthorization(head, 40,
						List.of(entryBlock.blockId()), entryBlock.faceTraversalKeys(), true));
				movingTrain.mtrbr$setRailProgress(head);
				movingTrain.mtrbr$setSpeed(0.01);
				check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 0, "white shunt refreshes cached red stop while moving");
				check(MovementGate.shouldDisableNativeBlock(movingTrain), "red main with shunt authority cannot impose native stop");
				check(MovementGate.clampStoppingPoint(movingTrain, 60) == 40, "moving train is limited to one shunt Block");
				check(MovementGate.clampStoppingPoint(movingTrain, head + 0.005) == head + 0.005, "scheduled stop inside shunt Block is retained");
				MovementGate.beforeVehicleSimulation(movingTrain);
				check(movingTrain.mtrbr$getSpeed() == 0.01, "passing the main does not zero speed");
			}
			set(vehicle, "head", 40.0); movingTrain.mtrbr$setRailProgress(40);
			check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 1000, "expired prefix does not bypass native stop");
			set(vehicle, "head", 25.0); movingTrain.mtrbr$setRailProgress(25);
			set(vehicle, "authorization", null);
			check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 1000, "no authority preserves native stopping cache");
			check(!MovementGate.shouldDisableNativeBlock(movingTrain), "denied shunt cannot bypass native block");
		} finally {
			if (previousState == null) map(RouteRequestManager.class, "STATES").remove(sim);
			else map(RouteRequestManager.class, "STATES").put(sim, previousState);
		}
		set(vehicle, "head", 35.0);
		check((double)call("authorizationBoundary", sim, vehicle, 40.0) == 50, "new request is allowed immediately after entry");
		call("refreshAuthorizationLookahead", sim, vehicle);
		check((double)get(vehicle, "authorizationLookaheadEndDistance") == 50, "lookahead advances without waiting at exit");
		final var rolling = call("clearancePrefix", sim, vehicle, 40.0, 60.0);
		check(((List<?>)recordValue(rolling, "blockAuthorizations")).size() == 1 && (double)recordValue(rolling, "endDistance") == 50,
				"rolling request receives only the next Block");
		set(vehicle, "head", 40.0);
		occupants("shunt4").add(998L);
		check(((List<?>)recordValue(call("clearancePrefix", sim, vehicle, 40.0, 50.0), "blockAuthorizations")).isEmpty(), "occupied exit Block keeps next main red");
		occupants("shunt4").clear();
		final var outgoing = (Authorization.BlockAuthorization)((List<?>)recordValue(call("clearancePrefix", sim, vehicle, 40.0, 50.0), "blockAuthorizations")).get(0);
		hold(outgoing, request(vehicle));
		final var outgoingAuth = new Authorization("shunt-exit-auth", request(vehicle).getRequestId(), List.of(outgoing), List.of(), 0, 12);
		set(vehicle, "authorization", outgoingAuth);
		set(vehicle, "authorizationEndDistance", 50.0);
		final var state = construct(nested(RouteRequestManager.class, "State"));
		final long vehicleId = ((Vehicle)get(vehicle, "vehicle")).getId();
		map(state, "vehicles").put(vehicleId, vehicle);
		final String exitKey = sim.dimension + "|" + faces.get(3).face().signalPos().asLong() + "|false";
		final var displayConstructor = nested(ServerAspectManager.class, "SignalDisplay").getDeclaredConstructor(ServerAspect.class, String.class, String.class, long.class, boolean.class);
		displayConstructor.setAccessible(true);
		final String code = RouteRequestManager.getVehicleCode(vehicleId);
		for (double head : new double[] {35, 39.99, 40, 40.008}) {
			set(vehicle, "publishedShuntExit", null);
			set(vehicle, "head", head);
			final var activity = new RouteRequestManager.ActivityAuthorization(head, 50, List.of(outgoing.blockId()), outgoing.faceTraversalKeys(), true);
			set(vehicle, "activityAuthorization", activity);
			map(ServerAspectManager.class, "ASPECTS").remove(exitKey);
			check((double)call("authorizedControlBoundary", sim, vehicle, activity, topology) == Math.max(head, 40), "approach holds at exit until signal publication");
			call("publishAuthorizations", sim, state);
			final var published = RouteRequestManager.getAuthorizedPaths(sim).get(0);
			check(published.startDistance() == Math.min(head, 40) && published.activeFaceTraversalKeys().contains(faces.get(3).key()), "exit face is published before arrival and survives rounding");
			final var covered = ServerAspectManager.class.getDeclaredMethod("coveredFaceTraversal", String.class, ServerAspectManager.FaceSnapshot.class, RouteRequestManager.AuthorizedPath.class, SignalFace.class);
			covered.setAccessible(true);
			check(covered.invoke(null, sim.dimension, topology, published, faces.get(3).face()) != null, "next main receives outgoing authorization");
			final var aspect = (ServerAspect)resolver.invoke(null, sim.dimension, topology, published, faces.get(3), new HashSet<>());
			check(aspect == ServerAspect.YELLOW, "next main changes red to yellow before departure");
			map(ServerAspectManager.class, "ASPECTS").put(exitKey, displayConstructor.newInstance(aspect, "another-vehicle", "", 12L, false));
			check((double)call("authorizedControlBoundary", sim, vehicle, activity, topology) == Math.max(head, 40), "another vehicle's green cannot release exit");
			map(ServerAspectManager.class, "ASPECTS").put(exitKey, displayConstructor.newInstance(aspect, code, "", 11L, false));
			check((double)call("authorizedControlBoundary", sim, vehicle, activity, topology) == Math.max(head, 40), "old clearance cannot release exit");
			map(ServerAspectManager.class, "ASPECTS").put(exitKey, displayConstructor.newInstance(ServerAspect.RED, code, "", 12L, false));
			check((double)call("authorizedControlBoundary", sim, vehicle, activity, topology) == Math.max(head, 40), "published red still stops vehicle");
			map(ServerAspectManager.class, "ASPECTS").put(exitKey, displayConstructor.newInstance(aspect, code, "", 12L, false));
			check((double)call("authorizedControlBoundary", sim, vehicle, activity, topology) == 50, "move only after current outgoing clearance is displayed");
		}
		set(vehicle, "head", 39.99);
		final var rollingActivity = new RouteRequestManager.ActivityAuthorization(39.99, 50, List.of(outgoing.blockId()), outgoing.faceTraversalKeys(), true);
		check((double)call("authorizedControlBoundary", sim, vehicle, rollingActivity, topology) == 50, "published exit allows continuous approach");
		set(vehicle, "head", 40.008);
		set(vehicle, "authorization", new Authorization("shunt-exit-auth", request(vehicle).getRequestId(), List.of(outgoing), List.of(), 0, 13));
		check((double)call("authorizedControlBoundary", sim, vehicle, rollingActivity, topology) == 50,
				"new revision after crossing an already-cleared main does not introduce a stop");
		set(vehicle, "authorization", outgoingAuth);
		map(ServerAspectManager.class, "ASPECTS").remove(exitKey);
		set(vehicle, "authorization", null);
		set(vehicle, "head", 25.0);
		final var deletionData = new org.mtrbr.data.RouteBindingsSavedData();
		set(deletionData, "dimension", sim.dimension);
		deletionData.set(shunt, destination, compound);
		final var indicator = shunt.above();
		final var secondIndicator = shunt.above(2);
		deletionData.setShuntIndicatorBinding(indicator, shunt);
		deletionData.setShuntIndicatorBinding(secondIndicator, shunt);
		deletionData.clearIndicatorBinding(indicator);
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 25) == 40, "remaining device still enables shunting");
		deletionData.clearIndicatorBinding(secondIndicator);
		check(deletionData.getBindings(shunt).get(0).content().equals(compound), "deletion preserves compound route content");
		check(ShuntSignalPolicy.route(sim.dimension, path, faces.get(2)).isEmpty(), "last device deletion disables shunt route selection");
		check(Double.isInfinite(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 25)), "last device deletion removes shunt-only stopping boundary");
		check(ShuntSignalPolicy.exitAtHead(sim.dimension, path, faces, 40) == null, "deleted device leaves no special exit gate");
		// All downstream faces in auth are active: terminal red -> yellow -> double yellow -> green.
		check(resolver.invoke(null, sim.dimension, topology, auth, faces.get(2), new HashSet<>()) == ServerAspect.GREEN,
				"deleted shunt main returns to green with downstream clearance");
		final var firstBlockOnly = new RouteRequestManager.AuthorizedPath(1, "TEST", path, path.getTraversals(), List.of(), 30, 40,
				List.of(faceBlocks.get(faces.get(2).faceId())), List.of(faces.get(2).key()), "test:first-block", 1);
		check(resolver.invoke(null, sim.dimension, topology, firstBlockOnly, faces.get(2), new HashSet<>()) == ServerAspect.YELLOW,
				"deleted shunt main warns of the next red when only its immediate Block is authorized");
		clearance = call("clearancePrefix", sim, vehicle, 30.0, 50.0);
		check(((List<?>)recordValue(clearance, "blockAuthorizations")).size() == 2, "normal clearance can span two clear Blocks after deletion");
		occupants("shunt3").add(999L);
		check(((List<?>)recordValue(call("clearancePrefix", sim, vehicle, 30.0, 50.0), "blockAuthorizations")).isEmpty(), "deletion does not bypass occupied Blocks");
		occupants("shunt3").clear();
		set(vehicle, "head", 35.0);
		set(vehicle, "authorization", outgoingAuth);
		final var staleActivity = new RouteRequestManager.ActivityAuthorization(30, 50, List.of(), List.of(), true);
		check((double)call("authorizedControlBoundary", sim, vehicle, staleActivity, topology) == 50,
				"deletion inside a Block retains valid ordinary clearance without an artificial stop at head");
		set(vehicle, "activityAuthorization", staleActivity);
		movingTrain.mtrbr$setRailProgress(35);
		map(RouteRequestManager.class, "STATES").put(sim, movingState);
		try {
			check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 0, "deletion with normal authorization discards cached shunt stop");
			check(MovementGate.clampStoppingPoint(movingTrain, 60) == 50, "deleted shunt no longer caps moving train at old exit");
		} finally {
			if (previousState == null) map(RouteRequestManager.class, "STATES").remove(sim);
			else map(RouteRequestManager.class, "STATES").put(sim, previousState);
		}
		set(vehicle, "authorization", new Authorization("empty", request(vehicle).getRequestId(), List.of(), List.of(), 0, 13));
		check((double)call("authorizedControlBoundary", sim, vehicle, staleActivity, topology) == 40,
				"deletion does not allow crossing the next main without a locked Block");
		deletionData.setShuntIndicatorBinding(indicator, shunt);
		check(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 35) == 50, "rebinding restores one-future-Block shunt protection");
		ShuntSignalPolicy.publish(sim.dimension, Map.of(shunt, List.of(new org.mtrbr.data.RouteBinding(destination, "route=1"))), Set.of(shunt));
		check(Double.isInfinite(ShuntSignalPolicy.boundary(sim.dimension, path, faces, 15)), "normal route is not shunting even with bound device");
		ShuntSignalPolicy.publish(sim.dimension, Map.of(shunt, List.of(new org.mtrbr.data.RouteBinding(new BlockPos(10, -60, 0), "shunt=behind"))), Set.of(shunt));
		check(ShuntSignalPolicy.route(sim.dimension, path, faces.get(2)).isEmpty(), "destination behind face must not select shunt");
		ShuntSignalPolicy.reset();
	}

	private static void shuntBindings() throws Exception {
		check("shunt=yard_1".equals(org.mtrbr.data.RouteContent.validate(" SHUNT=Yard_1 ")), "normalize shunt names");
		for (String invalid : List.of("shunt=", "shunt=a b", "shunt=" + "a".repeat(33)))
			check(org.mtrbr.data.RouteContent.validate(invalid) == null, "reject invalid shunt name");
		check("route=6".equals(org.mtrbr.data.RouteContent.validate("route=6")), "existing route validation");
		final var data = new org.mtrbr.data.RouteBindingsSavedData();
		final var indicator = new BlockPos(1, 2, 3);
		final var main = new BlockPos(4, 5, 6);
		set(data, "dimension", sim.dimension);
		data.setShuntIndicatorBinding(indicator, main);
		check(ShuntSignalPolicy.hasSignal(sim.dimension, main), "publish binding");
		final var tag = data.save(new net.minecraft.nbt.CompoundTag());
		final var loader = data.getClass().getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class);
		loader.setAccessible(true);
		final var restored = (org.mtrbr.data.RouteBindingsSavedData) loader.invoke(null, tag);
		set(restored, "dimension", sim.dimension); restored.setDirty();
		check(main.equals(restored.getIndicatorBinding(indicator)) && ShuntSignalPolicy.hasSignal(sim.dimension, main), "reload restores typed binding");
		restored.removeIndicatorBinding(indicator);
		check(!ShuntSignalPolicy.hasSignal(sim.dimension, main), "unbind removes authority");
		restored.setShuntIndicatorBinding(indicator, main); restored.clearIndicatorBinding(indicator);
		check(!ShuntSignalPolicy.hasSignal(sim.dimension, main), "break removes authority");
		restored.setShuntIndicatorBinding(indicator, main); restored.clearSignalBindings(main);
		check(!ShuntSignalPolicy.hasSignal(sim.dimension, main), "main deletion removes authority");
		restored.setShuntIndicatorBinding(indicator, main);
		final var unloadedIndicator = indicator.above();
		restored.setShuntIndicatorBinding(unloadedIndicator, main);
		check(restored.removeMissingShuntIndicators(indicator::equals), "repair stale saved binding at confirmed missing device");
		check(restored.getIndicatorBinding(indicator) == null, "repair removes stale map entry as well as typed membership");
		check(ShuntSignalPolicy.hasSignal(sim.dimension, main), "unloaded or still-present second device keeps authority");
		check(!restored.removeMissingShuntIndicators(indicator::equals), "repair is idempotent");
		check(restored.removeMissingShuntIndicators(unloadedIndicator::equals), "remove last device once confirmed missing");
		check(!ShuntSignalPolicy.hasSignal(sim.dimension, main), "stale-save repair clears last shunt authority");
		ShuntSignalPolicy.reset();
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
