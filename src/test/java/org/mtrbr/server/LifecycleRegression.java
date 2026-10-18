package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import org.mtr.core.data.Vehicle;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtrbr.data.SignalBlockSavedData;

import org.mtrbr.server.RouteRuntime.VehicleState;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.Clearance;
import java.util.*;

/** Typed integration scenarios exercising production route services and published snapshots. */
final class LifecycleRegression {
	private static Simulator sim;
	private static int passed;

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
		test("confirmed removals preserve terminal request states and release all protection", LifecycleRegression::confirmedRemovals);
		test("another dimension cannot consume a confirmed vehicle removal", LifecycleRegression::dimensionRemovalIsolation);
		test("server reset discards queued mapping retries and published runtime state", LifecycleRegression::shutdownReset);
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
		RouteAuthorizationLifecycle.invalidateAuthorization(sim, v, ReleaseReason.INVALID, RequestState.INVALID);
		check(v.authorization == null && !protection(v).isEmpty(), "retire authority, retain protection");
		var next = new RouteRequest(old.getVehicleId(), "refreshed", 2, 2, b.sectionIds(), List.of(), List.of());
		RouteAuthorizationLifecycle.adoptRetainedResources(sim, v, old, next); v.request = next;
		sweep(v);
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(b), next.getRequestId()), "new owner survives stale sweep");
		check(!SectionStateManager.areSectionsAvailable(sim, b.sectionIds(), "third", 999, false), "another train remains denied");
		check(JunctionStateManager.areResourcesReservedAndLockedBy(sim, b.junctionMovementIds(), next.getRequestId()), "junction transferred without loss");
		SectionStateManager.releaseSections(sim, b.sectionIds(), old.getRequestId());
		check(SectionStateManager.getSections(sim).get("h2").lockedBy.contains(next.getRequestId()), "old owner cannot unlock new lease");
		RouteAuthorizationLifecycle.releasePendingReleaseOccupancy(sim, v);
		check(SectionStateManager.isBlockConflicted(sim, b.blockId(), "third"), "still occupied after request refresh");
		v.sections = Set.of(); RouteAuthorizationLifecycle.releasePendingReleaseOccupancy(sim, v);
		check(SectionStateManager.areBlocksAvailable(sim, ids(b), "third"), "correct owner released after clearance");
		check(SectionStateManager.areSectionsAvailable(sim, b.sectionIds(), "third", 999, false), "sections released together");
		check(!JunctionStateManager.conflicts(sim, b.junctionMovementIds(), "third"), "junction released together");
	}

	private static void requestless() throws Exception {
		var b = block("requestless", List.of("r1"), 10); var v = vehicle(b, Set.of("r1")); hold(b, request(v));
		RouteAuthorizationLifecycle.invalidateAuthorization(sim, v, ReleaseReason.INVALID, RequestState.INVALID);
		v.request = null; v.managed = false;
		sweep(v);
		check(SectionStateManager.isBlockConflicted(sim, b.blockId(), "other"), "pending retains its own owner without request");
		v.sections = Set.of(); v.observed = true;
		State state = new State();
		state.vehicles.put(((Vehicle)v.vehicle).getId(), v);
		RouteRuntime.STATES.put(sim, state);
		RouteRequestManager.finishSimulationTick(sim);
		check(protection(v).isEmpty(), "unmanaged tick must process pending cleanup");
		check(SectionStateManager.areBlocksAvailable(sim, ids(b), "other"), "requestless exact-owner release");
		RouteRuntime.STATES.remove(sim);
	}

	private static void cycleReset() throws Exception {
		var b = block("cycle", List.of("c1", "c2"), 10); var v = vehicle(b, Set.of("c1")); hold(b, request(v));
		v.head = 0.0; v.tail = -5.0; v.fixedGateBoundary = 200.0; v.fixedGateBoundarySignature = "old-cycle";
		RouteAuthorizationLifecycle.resetRouteCycle(sim, v);
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(b), request(v).getRequestId()), "distance reset cannot release occupied block");
		check(JunctionStateManager.conflicts(sim, b.junctionMovementIds(), "other"), "junction still protected");
		check(request(v).getState() == RequestState.INVALID && v.authorization == null, "old movement authority invalid");
		check(((String)v.fixedGateBoundarySignature).isEmpty(), "old gate identity discarded");
		v.sections = Set.of(); RouteAuthorizationLifecycle.releasePendingReleaseOccupancy(sim, v);
	}

	private static void containingRecovery() throws Exception {
		var path = path("recovery", List.of("p1", "p2", "p3"));
		var entry = new SignalFace("entry", BlockPos.ZERO, new BlockPos(0, -60, 0), false, 0);
		var snapshot = new ServerAspectManager.FaceSnapshot(Map.of(entry.id(), entry), 781);
		publishTopology(Map.of(sim.dimension, snapshot));
		var faces = path.getFaceTraversals(sim.dimension, snapshot);
		check(faces.size() == 1 && faces.get(0).pathTraversalIndex() == 0, "real node-to-occurrence projection");
		var b = block("recovery", List.of("p1", "p2", "p3"), 30); var v = vehicle(b, Set.of("p2"));
		v.path = path; v.head = 15.0; v.controlDistance = 15.0;
		var range = RouteAuthorizationPlanner.findControlRange(sim, v);
		check(range != null && ((Double)range.controlDistance()) == 15.0, "recover containing block from current head, no future signal");
		check(RouteAuthorizationPlanner.containingBlockEntry(path, faces, 30.0) == null, "terminal equality must not borrow preceding block");
		var definition = RouteProjection.define(sim, path, faces, faces.get(0));
		publishBlocks(Map.of(sim.dimension, new SignalBlockSavedData.Snapshot(
				Map.of(entry.id(), definition.blockDefinitionId()), Map.of(SignalBlockSavedData.occurrenceKey(path.getFingerprint(), faces.get(0).key()), definition.blockDefinitionId()),
				Map.of(definition.blockDefinitionId(), definition.sectionIds()), Map.of())));
		for (String id : b.sectionIds()) section(id);
		var clearance = RouteAuthorizationPlanner.clearancePrefix(sim, v, 15.0, 30.0);
		check(!((List<?>)clearance.blockAuthorizations()).isEmpty(), "passed entry face must still produce validated authorization");
		occupants("p3").add(99L);
		clearance = RouteAuthorizationPlanner.clearancePrefix(sim, v, 15.0, 20.0);
		check(((List<?>)clearance.blockAuthorizations()).isEmpty(), "short recovery denied by occupancy beyond movement limit");
		occupants("p3").clear();
		publishBlocks(Map.of());
		clearance = RouteAuthorizationPlanner.clearancePrefix(sim, v, 15.0, 30.0);
		check(((List<?>)clearance.blockAuthorizations()).isEmpty(), "containing block cannot bypass missing mapping");
		publishTopology(Map.of());
	}

	private static void sharedBlock() throws Exception {
		var first = block("shared", List.of("s1", "s2"), 10); var second = block("shared", List.of("s1", "s2"), 20);
		var v = vehicle(first, Set.of("s2")); hold(first, request(v)); hold(second, request(v));
		v.authorization = new Authorization("shared-auth", request(v).getRequestId(), List.of(first, second), List.of(), 0, 0);
		v.tail = 15.0;
		RouteAuthorizationLifecycle.releaseAuthorizationPastHead(sim, v);
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(first), request(v).getRequestId()), "tail passed prefix but whole block remains occupied");
		var releasable = protection(v).releasableBlockLockIds(ids(first), List.of(second));
		check(!releasable.contains(first.blockId()) && releasable.contains(first.occurrenceId()), "shared canonical ID must outlive individual prefix");
		RouteAuthorizationLifecycle.invalidateAuthorization(sim, v, ReleaseReason.INVALID, RequestState.INVALID);
		v.sections = Set.of(); RouteAuthorizationLifecycle.releasePendingReleaseOccupancy(sim, v);
		check(SectionStateManager.areBlocksAvailable(sim, List.of(first.blockId()), "other"), "last pending reference releases canonical ID");
	}

	private static void requestEnd() throws Exception {
		var b = block("end", List.of("e1", "e2"), 20); var v = vehicle(b, Set.of("e2")); hold(b, request(v));
		v.endDistance = 20.0; v.head = 25.0; v.tail = 21.0;
		RouteAuthorizationLifecycle.updateAuthorizedLifecycle(sim, v);
		check(request(v).getState() == RequestState.ACTIVE, "distance disagreement must not release or mark passed");
		check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(b), request(v).getRequestId()), "occupied request-end block retained");
		v.sections = Set.of(); RouteAuthorizationLifecycle.updateAuthorizedLifecycle(sim, v);
		check(request(v).getState() == RequestState.RELEASED, "confirmed physical clearance completes Request");
		check(SectionStateManager.areBlocksAvailable(sim, ids(b), "other"), "cleared Request releases protection");
	}

	private static void wrongTrain() throws Exception {
		var b = block("identity", List.of("i1"), 10); var v = vehicle(b, Set.of());
		var other = new RouteRequest(request(v).getVehicleId() + 1, "other", 2, 1, List.of(), List.of(), List.of());
		try { RouteAuthorizationLifecycle.adoptRetainedResources(sim, v, request(v), other); throw new AssertionError("cross-train transfer allowed"); }
		catch (IllegalArgumentException expected) { check(expected.getMessage().contains("across trains"), "identity guard"); }
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
		publishTopology(Map.of(sim.dimension, topology));
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
		publishBlocks(Map.of(sim.dimension, new SignalBlockSavedData.Snapshot(faceBlocks, occurrences, blockRails, Map.of())));
		for (String rail : rails) section(rail);
		final VehicleState vehicle = vehicle(block("shunt", rails, 50), Set.of());
		vehicle.authorization = null;
		vehicle.head = 25.0;
		vehicle.endDistance = 50.0;
		vehicle.controlDistance = 30.0;
		vehicle.authorizationLookaheadEndDistance = 50.0;
		Clearance clearance = RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 30.0, 50.0);
		check(((List<?>)clearance.blockAuthorizations()).size() == 1, "production clearance grants exactly one Block");
		check((double)clearance.endDistance() == 40, "production authorization stops at next face");
		check((double)RouteAuthorizationPlanner.authorizationBoundary(sim, vehicle, 40.0) == 40, "extension start cannot move shunt limit");
		RouteAuthorizationLifecycle.refreshAuthorizationLookahead(sim, vehicle);
		check((double)vehicle.authorizationLookaheadEndDistance == 40, "moving preview cannot open second block");
		occupants("shunt4").add(998L);
		clearance = RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 30.0, 50.0);
		check(((List<?>)clearance.blockAuthorizations()).size() == 1, "second-block occupation does not deny first-block permission");
		occupants("shunt3").add(999L);
		clearance = RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 30.0, 50.0);
		check(((List<?>)clearance.blockAuthorizations()).isEmpty(), "first-block occupation denies shunt permission");
		occupants("shunt3").clear(); occupants("shunt4").clear();
		final var auth = new RouteRequestManager.AuthorizedPath(1, "TEST", path, path.getTraversals(), List.of(), 0, 60,
				new ArrayList<>(faceBlocks.values()), faces.stream().map(PathSnapshot.FaceTraversal::key).toList(), "test:auth", 1);

		check(ServerAspectManager.resolveAspect(sim.dimension, topology, auth, faces.get(2), new HashSet<>()) == ServerAspect.RED, "shunt main stays red");
		check(ServerAspectManager.resolveAspect(sim.dimension, topology, auth, faces.get(1), new HashSet<>()) == ServerAspect.YELLOW, "previous main becomes yellow");
		check(ServerAspectManager.resolveAspect(sim.dimension, topology, auth, faces.get(0), new HashSet<>()) == ServerAspect.DOUBLE_YELLOW, "second previous main becomes double yellow");
		final var entryClearance = RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 30.0, 50.0);
		final var entryBlock = (Authorization.BlockAuthorization)((List<?>)entryClearance.blockAuthorizations()).get(0);
		hold(entryBlock, request(vehicle));
		final var entryAuth = new Authorization("shunt-moving-auth", request(vehicle).getRequestId(), List.of(entryBlock), List.of(), 0, 10);
		vehicle.authorization = entryAuth;
		final var movingState = new State();
		final TestVehicle movingTrain = (TestVehicle)vehicle.vehicle;
		movingState.vehicles.put(movingTrain.getId(), vehicle);
		final State previousState = RouteRuntime.STATES.put(sim, movingState);
		try {
			for (double head : new double[] {25, 29.99, 30, 35, 39.99}) {
				vehicle.head = head;
				vehicle.activityAuthorization = new RouteRequestManager.ActivityAuthorization(head, 40,
						List.of(entryBlock.blockId()), entryBlock.faceTraversalKeys(), true);
				movingTrain.mtrbr$setRailProgress(head);
				movingTrain.mtrbr$setSpeed(0.01);
				check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 0, "white shunt refreshes cached red stop while moving");
				check(MovementGate.shouldDisableNativeBlock(movingTrain), "red main with shunt authority cannot impose native stop");
				check(MovementGate.clampStoppingPoint(movingTrain, 60) == 40, "moving train is limited to one shunt Block");
				check(MovementGate.clampStoppingPoint(movingTrain, head + 0.005) == head + 0.005, "scheduled stop inside shunt Block is retained");
				MovementGate.beforeVehicleSimulation(movingTrain);
				check(movingTrain.mtrbr$getSpeed() == 0.01, "passing the main does not zero speed");
			}
			vehicle.head = 40.0; movingTrain.mtrbr$setRailProgress(40);
			check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 1000, "expired prefix does not bypass native stop");
			vehicle.head = 25.0; movingTrain.mtrbr$setRailProgress(25);
			vehicle.authorization = null;
			check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 1000, "no authority preserves native stopping cache");
			check(!MovementGate.shouldDisableNativeBlock(movingTrain), "denied shunt cannot bypass native block");
		} finally {
			if (previousState == null) RouteRuntime.STATES.remove(sim);
			else RouteRuntime.STATES.put(sim, previousState);
		}
		vehicle.head = 35.0;
		check((double)RouteAuthorizationPlanner.authorizationBoundary(sim, vehicle, 40.0) == 50, "new request is allowed immediately after entry");
		RouteAuthorizationLifecycle.refreshAuthorizationLookahead(sim, vehicle);
		check((double)vehicle.authorizationLookaheadEndDistance == 50, "lookahead advances without waiting at exit");
		final var rolling = RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 40.0, 60.0);
		check(((List<?>)rolling.blockAuthorizations()).size() == 1 && (double)rolling.endDistance() == 50,
				"rolling request receives only the next Block");
		vehicle.head = 40.0;
		occupants("shunt4").add(998L);
		check(((List<?>)RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 40.0, 50.0).blockAuthorizations()).isEmpty(), "occupied exit Block keeps next main red");
		occupants("shunt4").clear();
		final var outgoing = (Authorization.BlockAuthorization)((List<?>)RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 40.0, 50.0).blockAuthorizations()).get(0);
		hold(outgoing, request(vehicle));
		final var outgoingAuth = new Authorization("shunt-exit-auth", request(vehicle).getRequestId(), List.of(outgoing), List.of(), 0, 12);
		vehicle.authorization = outgoingAuth;
		vehicle.authorizationEndDistance = 50.0;
		final var state = new State();
		final long vehicleId = ((Vehicle)vehicle.vehicle).getId();
		state.vehicles.put(vehicleId, vehicle);
		final String exitKey = sim.dimension + "|" + faces.get(3).face().signalPos().asLong() + "|false";

		final String code = RouteRequestManager.getVehicleCode(vehicleId);
		for (double head : new double[] {35, 39.99, 40, 40.008}) {
			vehicle.publishedShuntExit = null;
			vehicle.head = head;
			final var activity = new RouteRequestManager.ActivityAuthorization(head, 50, List.of(outgoing.blockId()), outgoing.faceTraversalKeys(), true);
			vehicle.activityAuthorization = activity;
			removeDisplay(exitKey);
			check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, activity, topology) == Math.max(head, 40), "approach holds at exit until signal publication");
			RouteDispatchSnapshots.publishAuthorizations(sim, state);
			final var published = RouteRequestManager.getAuthorizedPaths(sim).get(0);
			check(published.startDistance() == Math.min(head, 40) && published.activeFaceTraversalKeys().contains(faces.get(3).key()), "exit face is published before arrival and survives rounding");

			check(ServerAspectManager.coveredFaceTraversal(sim.dimension, topology, published, faces.get(3).face()) != null, "next main receives outgoing authorization");
			final var aspect = (ServerAspect)ServerAspectManager.resolveAspect(sim.dimension, topology, published, faces.get(3), new HashSet<>());
			check(aspect == ServerAspect.YELLOW, "next main changes red to yellow before departure");
			putDisplay(exitKey, new ServerAspectManager.SignalDisplay(aspect, "another-vehicle", "", 12L, false));
			check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, activity, topology) == Math.max(head, 40), "another vehicle's green cannot release exit");
			putDisplay(exitKey, new ServerAspectManager.SignalDisplay(aspect, code, "", 11L, false));
			check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, activity, topology) == Math.max(head, 40), "old clearance cannot release exit");
			putDisplay(exitKey, new ServerAspectManager.SignalDisplay(ServerAspect.RED, code, "", 12L, false));
			check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, activity, topology) == Math.max(head, 40), "published red still stops vehicle");
			putDisplay(exitKey, new ServerAspectManager.SignalDisplay(aspect, code, "", 12L, false));
			check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, activity, topology) == 50, "move only after current outgoing clearance is displayed");
		}
		vehicle.head = 39.99;
		final var rollingActivity = new RouteRequestManager.ActivityAuthorization(39.99, 50, List.of(outgoing.blockId()), outgoing.faceTraversalKeys(), true);
		check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, rollingActivity, topology) == 50, "published exit allows continuous approach");
		vehicle.head = 40.008;
		vehicle.authorization = new Authorization("shunt-exit-auth", request(vehicle).getRequestId(), List.of(outgoing), List.of(), 0, 13);
		check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, rollingActivity, topology) == 50,
				"new revision after crossing an already-cleared main does not introduce a stop");
		vehicle.authorization = outgoingAuth;
		removeDisplay(exitKey);
		vehicle.authorization = null;
		vehicle.head = 25.0;
		final var deletionData = new org.mtrbr.data.RouteBindingsSavedData();
		deletionData.attachDimension(sim.dimension);
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
		check(ServerAspectManager.resolveAspect(sim.dimension, topology, auth, faces.get(2), new HashSet<>()) == ServerAspect.GREEN,
				"deleted shunt main returns to green with downstream clearance");
		final var firstBlockOnly = new RouteRequestManager.AuthorizedPath(1, "TEST", path, path.getTraversals(), List.of(), 30, 40,
				List.of(faceBlocks.get(faces.get(2).faceId())), List.of(faces.get(2).key()), "test:first-block", 1);
		check(ServerAspectManager.resolveAspect(sim.dimension, topology, firstBlockOnly, faces.get(2), new HashSet<>()) == ServerAspect.YELLOW,
				"deleted shunt main warns of the next red when only its immediate Block is authorized");
		clearance = RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 30.0, 50.0);
		check(((List<?>)clearance.blockAuthorizations()).size() == 2, "normal clearance can span two clear Blocks after deletion");
		occupants("shunt3").add(999L);
		check(((List<?>)RouteAuthorizationPlanner.clearancePrefix(sim, vehicle, 30.0, 50.0).blockAuthorizations()).isEmpty(), "deletion does not bypass occupied Blocks");
		occupants("shunt3").clear();
		vehicle.head = 35.0;
		vehicle.authorization = outgoingAuth;
		final var staleActivity = new RouteRequestManager.ActivityAuthorization(30, 50, List.of(), List.of(), true);
		check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, staleActivity, topology) == 50,
				"deletion inside a Block retains valid ordinary clearance without an artificial stop at head");
		vehicle.activityAuthorization = staleActivity;
		movingTrain.mtrbr$setRailProgress(35);
		RouteRuntime.STATES.put(sim, movingState);
		try {
			check(MovementGate.nativeStoppingCooldown(movingTrain, 1000) == 0, "deletion with normal authorization discards cached shunt stop");
			check(MovementGate.clampStoppingPoint(movingTrain, 60) == 50, "deleted shunt no longer caps moving train at old exit");
		} finally {
			if (previousState == null) RouteRuntime.STATES.remove(sim);
			else RouteRuntime.STATES.put(sim, previousState);
		}
		vehicle.authorization = new Authorization("empty", request(vehicle).getRequestId(), List.of(), List.of(), 0, 13);
		check((double)RouteMovementControl.authorizedControlBoundary(sim, vehicle, staleActivity, topology) == 40,
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
		data.attachDimension(sim.dimension);
		data.setShuntIndicatorBinding(indicator, main);
		check(ShuntSignalPolicy.hasSignal(sim.dimension, main), "publish binding");
		final var tag = data.save(new net.minecraft.nbt.CompoundTag());

		final var restored = (org.mtrbr.data.RouteBindingsSavedData) org.mtrbr.data.RouteBindingsSavedData.load(tag);
		restored.attachDimension(sim.dimension); restored.setDirty();
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

	private static void dimensionRemovalIsolation() throws Exception {
		var b = block("dimension-removal", List.of("dimension-rail"), 10);
		var v = vehicle(b, Set.of("dimension-rail"));
		hold(b, v.request);
		RouteAuthorizationLifecycle.invalidateAuthorization(sim, v, ReleaseReason.INVALID, RequestState.INVALID);
		var state = new State();
		long id = v.vehicle.getId();
		state.vehicles.put(id, v);
		RouteRuntime.STATES.put(sim, state);
		var other = new Simulator("unrelated-removal", new String[] { "unrelated-removal" },
				java.nio.file.Path.of("simulation", "unrelated-removal"), false);
		String code = RouteRequestManager.getVehicleCode(id);
		try {
			RouteRequestManager.onMtrVehicleRemoved(v.vehicle, "DIMENSION_FIXTURE");
			SectionStateManager.beginSimulation(other);
			RouteRequestManager.finishSimulationTick(other);
			check(Objects.equals(RouteRequestManager.resolveVehicleCode(code), id), "unrelated tick cannot discard another dimension's code");
			check(SectionStateManager.areBlocksReservedAndLockedBy(sim, ids(b), v.request.getRequestId()), "protection retained until owning simulation processes removal");
			SectionStateManager.beginSimulation(sim);
			RouteRequestManager.finishSimulationTick(sim);
			check(!state.vehicles.containsKey(id), "owning simulation consumes queued removal");
			check(protection(v).isEmpty() && SectionStateManager.areBlocksAvailable(sim, ids(b), "other"), "owning simulation releases protection");
		} finally {
			RouteRuntime.STATES.remove(other);
			RouteRuntime.STATES.remove(sim);
			SectionStateManager.beginSimulation(sim);
		}
	}

	private static void shutdownReset() throws Exception {
		RouteRequestManager.notifyProtectionMappingsChanged(sim.dimension);
		RouteRequestManager.getVehicleCode(987654);
		check(!((Set<?>)RouteRuntime.PENDING_MAPPING_RETRIES).isEmpty(), "fixture has queued mapping retry");
		RouteRequestManager.resetAll();
		check(((Set<?>)RouteRuntime.PENDING_MAPPING_RETRIES).isEmpty(), "old server mapping retries discarded");
		check(RouteRequestManager.getAuthorizedPaths(sim).isEmpty() && RouteRequestManager.getRequestSnapshots(sim).isEmpty()
				&& RouteRequestManager.getVehicleSnapshots(sim).isEmpty() && RouteRequestManager.getAudit(sim).isEmpty(), "published runtime state discarded");
		check(RouteRequestManager.getVehicleCode(987654).equals("0000"), "vehicle code registry restarts");
	}

	private static void confirmedRemovals() throws Exception {
		for (String source : List.of("manual", "quarantine", "mtr")) {
			for (RequestState terminal : List.of(RequestState.INVALID, RequestState.CANCELED, RequestState.REVOKED,
					RequestState.PASSED, RequestState.RELEASED, RequestState.ACTIVE)) {
				String name = "removed-" + source + "-" + terminal;
				var b = block(name, List.of(name), 10);
				var v = vehicle(b, Set.of(name));
				var request = request(v);
				hold(b, request);
				if (terminal == RequestState.INVALID || terminal == RequestState.CANCELED || terminal == RequestState.REVOKED) {
					RouteAuthorizationLifecycle.invalidateAuthorization(sim, v, ReleaseReason.INVALID, terminal);
				} else {
					request.transitionTo(RequestState.ACTIVE, "removal fixture");
					if (terminal != RequestState.ACTIVE) request.transitionTo(RequestState.PASSED, "removal fixture");
					if (terminal == RequestState.RELEASED) request.transitionTo(RequestState.RELEASED, "removal fixture");
				}
				State state = new State();
				long id = request.getVehicleId();
				state.vehicles.put(id, v);
				RouteRuntime.STATES.put(sim, state);
				try {
					if (source.equals("mtr")) {
						RouteRequestManager.onMtrVehicleRemoved((Vehicle)v.vehicle, name);
						RouteRequestManager.finishSimulationTick(sim);
					} else if (source.equals("quarantine")) {
						v.missingTicks = 3;
						check(RouteRequestManager.confirmQuarantinedVehicleRemoval(sim, id), "quarantine cleanup succeeds");
					} else {
						check(RouteRequestManager.forceRemoveVehicle(sim, id), "manual cleanup succeeds");
					}
					check(!state.vehicles.containsKey(id), "confirmed removal discards vehicle state");
					check(protection(v).isEmpty(), "confirmed removal discards pending protection");
					check(request.getState() == (terminal == RequestState.ACTIVE ? RequestState.CANCELED : terminal), "terminal request is not canceled again");
					check(SectionStateManager.areBlocksAvailable(sim, ids(b), "other"), "confirmed removal releases blocks");
					check(SectionStateManager.areSectionsAvailable(sim, b.sectionIds(), "other", 999, false), "confirmed removal releases sections");
					check(!JunctionStateManager.conflicts(sim, b.junctionMovementIds(), "other"), "confirmed removal releases junctions");
				} finally {
					RouteRuntime.STATES.remove(sim);
				}
			}
		}
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
		return new PathSnapshot(sections, name);
	}
	private static VehicleState vehicle(Authorization.BlockAuthorization b, Set<String> occupied) throws Exception {
		VehicleState v = new VehicleState(); Vehicle train = new TestVehicle();
		var r = new RouteRequest(train.getId(), b.pathFingerprint(), 1, 1, b.sectionIds(), b.traversals(), List.of());
		for (var stage : List.of(RequestState.APPROACHING, RequestState.REQUESTED, RequestState.CHECKING, RequestState.WAITING, RequestState.AUTHORIZED)) r.transitionTo(stage, "fixture");
		v.vehicle = train; v.request = r; v.path = path(b.pathFingerprint(), b.sectionIds());
		v.head = 15.0; v.tail = 5.0; v.sections = occupied; v.managed = true;
		v.authorization = new Authorization("auth-" + b.blockId(), r.getRequestId(), List.of(b), List.of(), 0, 0);
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
	private static void sweep(VehicleState v) throws Exception {
		Map<String, Set<String>> sections = new HashMap<>(), blocks = new HashMap<>(), junctions = new HashMap<>();
		RouteAuthorizationLifecycle.retainPendingOwners(v, sections, blocks, junctions);
		SectionStateManager.releaseStaleReservations(sim, Set.of(), sections, blocks);
		JunctionStateManager.releaseStale(sim, Set.of(), junctions);
	}
	private static final Map<String, ServerAspectManager.SignalDisplay> displays = new HashMap<>();
	private static void putDisplay(String key, ServerAspectManager.SignalDisplay display) {
		displays.put(key, display);
		ServerAspectManager.publishDisplays(sim.dimension, displays);
	}
	private static void removeDisplay(String key) {
		displays.remove(key);
		ServerAspectManager.publishDisplays(sim.dimension, displays);
	}
	private static void publishTopology(Map<String, ServerAspectManager.FaceSnapshot> snapshots) {
		var snapshot = snapshots.getOrDefault(sim.dimension, new ServerAspectManager.FaceSnapshot(Map.of(), 0));
		ServerAspectManager.publishFaces(sim.dimension, snapshot.faces(), snapshot.revision());
	}
	private static void publishBlocks(Map<String, SignalBlockSavedData.Snapshot> snapshots) {
		var snapshot = snapshots.getOrDefault(sim.dimension, new SignalBlockSavedData.Snapshot(Map.of(), Map.of(), Map.of(), Map.of()));
		Map<String, RouteRequestManager.GeneratedProtection> faces = new HashMap<>(), occurrences = new HashMap<>();
		snapshot.faceToBlock().forEach((face, block) -> faces.put(face, new RouteRequestManager.GeneratedProtection(block, snapshot.getRailIds(block), snapshot.getBoundaryId(block))));
		snapshot.occurrenceToBlock().forEach((occurrence, block) -> occurrences.put(occurrence, new RouteRequestManager.GeneratedProtection(block, snapshot.getRailIds(block), snapshot.getBoundaryId(block))));
		var data = new SignalBlockSavedData();
		data.setSingleLineZones(snapshot.singleLineZones().values());
		data.replaceGeneratedMappings(sim.dimension, faces, occurrences);
	}
	private static void section(String id) {
		var facts = SectionStateManager.simulationState(sim);
		facts.sections.computeIfAbsent(id, SectionStateManager.SectionRecord::new).exists = true;
	}
	private static Set<Long> occupants(String id) {
		return SectionStateManager.simulationState(sim).sections.get(id).occupiedBy;
	}
	private static List<String> ids(Authorization.BlockAuthorization b) { return RetainedProtection.blockLockIds(List.of(b)); }
	private static RetainedProtection protection(VehicleState v) throws Exception { return (RetainedProtection)v.retainedProtection; }
	private static RouteRequest request(VehicleState v) throws Exception { return (RouteRequest)v.request; }
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
