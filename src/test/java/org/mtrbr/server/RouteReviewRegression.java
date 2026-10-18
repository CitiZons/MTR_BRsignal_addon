package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.loading.FMLPaths;
import org.mtr.core.data.Vehicle;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtrbr.server.RouteRuntime.State;
import org.mtrbr.server.RouteRuntime.VehicleState;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Independent review scenarios against real route/resource operations, without private reflection. */
public final class RouteReviewRegression {
    private static int cases, failures;
    private static final List<RequestState> TERMINAL = List.of(RequestState.CANCELED, RequestState.REVOKED,
            RequestState.INVALID, RequestState.PASSED, RequestState.RELEASED);

    public static void main(String[] args) {
        FMLPaths.loadAbsolutePaths(Path.of(".").toAbsolutePath().normalize());
        run("terminal invalidation preserves state and occupied protection", RouteReviewRegression::terminalInvalidation);
        run("terminal cleanup completes despite stale topology", RouteReviewRegression::terminalTick);
        run("dispatcher revocation rejects terminal requests", RouteReviewRegression::terminalRevoke);
        run("dispatcher approval rejects terminal requests without mutation", RouteReviewRegression::terminalApprove);
        run("one-shot override rejects terminal requests", RouteReviewRegression::terminalOverride);
        run("dispatcher commands reject requests not yet ready", RouteReviewRegression::prematureCommands);
        run("dispatcher approval rejects stale topology without mutation", RouteReviewRegression::staleApproval);
        run("manual approval retries denied requests through legal transitions", RouteReviewRegression::manualApproval);
        run("live dispatcher revocation retains physical protection", RouteReviewRegression::liveRevoke);
        run("junction metadata lives until the last owned resource clears", RouteReviewRegression::ownerRelease);
        run("handover reclaims old owner metadata without unlocking new owner", RouteReviewRegression::ownerTransfer);
        run("stale cleanup retains pending owners and reclaims orphan metadata", RouteReviewRegression::ownerSweep);
        run("empty and failed reservations do not retain owner metadata", RouteReviewRegression::ownerFailure);
        run("audit history retains only the latest 256 events", RouteReviewRegression::auditBound);
        run("audit publication reuses unchanged snapshots and freezes older snapshots", RouteReviewRegression::auditSnapshots);
        if (failures > 0) throw new AssertionError("Route review regression: " + failures + "/" + cases + " cases failed");
        System.out.println("Route review regression: " + cases + " cases passed.");
    }

    private static VehicleState vehicle(RetainedProtectionRegression.Fixture f, RequestState target) {
        var block = f.block("terminal", 0, List.of(f.first, f.second));
        var v = new VehicleState();
        v.vehicle = new TestVehicle();
        v.path = new PathSnapshot(List.of(new PathSnapshot.PathSection(f.first, 0, 10, "stale-fingerprint",
                new BlockPos(0, -60, 0), new BlockPos(10, -60, 0), 0, false, 0, false, false, 0, false)), "path");
        v.request = new RouteRequest(v.vehicle.getId(), "path", 1, 1, block.sectionIds(), List.of(), List.of());
        for (RequestState state : List.of(RequestState.APPROACHING, RequestState.REQUESTED, RequestState.CHECKING,
                RequestState.WAITING, RequestState.AUTHORIZED)) v.request.transitionTo(state, "fixture");
        v.head = 5; v.tail = 0; v.endDistance = 10; v.authorizationEndDistance = 10;
        v.managed = true; v.observed = true; v.sections = Set.of(f.first);
        v.authorization = new Authorization("fixture-auth", v.request.getRequestId(), List.of(block), List.of(), 0, 0);
        f.hold(block, v.request.getRequestId());
        if (target == RequestState.PASSED || target == RequestState.RELEASED) {
            v.request.transitionTo(RequestState.ACTIVE, "fixture");
            v.request.transitionTo(RequestState.PASSED, "fixture");
        }
        v.request.transitionTo(target, "fixture");
        return v;
    }

    private static void terminalInvalidation(RetainedProtectionRegression.Fixture f) {
        for (RequestState state : TERMINAL) {
            var v = vehicle(f, state);
            for (RequestState requested : List.of(RequestState.INVALID, RequestState.CHECKING, RequestState.REVOKED, RequestState.CANCELED)) {
                RouteAuthorizationLifecycle.invalidateAuthorization(f.simulator, v, ReleaseReason.INVALID, requested);
                check(v.request.getState() == state, "terminal " + state + " must survive requested " + requested);
                check(v.authorization == null && !v.retainedProtection.isEmpty(), "retire authority but retain occupied protection");
                check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, List.of("terminal"), v.request.getRequestId()), "occupied block stays locked");
            }
            RouteAuthorizationLifecycle.releaseAll(f.simulator, v);
        }
    }

    private static void terminalTick(RetainedProtectionRegression.Fixture f) {
        for (RequestState state : TERMINAL) {
            var v = vehicle(f, state);
            RouteAuthorizationLifecycle.invalidateAuthorization(f.simulator, v, ReleaseReason.INVALID, state);
            check(!v.path.matchesTopology(f.simulator), "fixture requires stale topology");
            var runtime = new State();
            runtime.vehicles.put(v.vehicle.getId(), v);
            RouteRuntime.STATES.put(f.simulator, runtime);
            RouteRequestManager.finishSimulationTick(f.simulator);
            check(v.request.getState() == state, "occupied terminal state preserved through stale topology tick");
            v.sections = Set.of(); v.observed = true;
            RouteRequestManager.finishSimulationTick(f.simulator);
            check(v.request.getState() == RequestState.RELEASED && v.retainedProtection.isEmpty(), "physical clearance completes terminal cleanup");
            check(SectionStateManager.areBlocksAvailable(f.simulator, List.of("terminal"), "other"), "cleared terminal block released");
        }
    }

    private static void terminalRevoke(RetainedProtectionRegression.Fixture f) {
        for (RequestState state : TERMINAL) {
            var v = vehicle(f, state);
            var auth = v.authorization;
            var result = RouteAuthorizationLifecycle.revokeAuthorization(f.simulator, v);
            check(!result.accepted() && result.reason().equals("request_terminal"), "terminal command is rejected with an explicit result");
            check(v.request.getState() == state && v.authorization == auth, "rejected command must not partially mutate authority");
            RouteAuthorizationLifecycle.releaseAll(f.simulator, v);
        }
    }

    private static void terminalApprove(RetainedProtectionRegression.Fixture f) {
        for (RequestState state : TERMINAL) {
            var v = vehicle(f, state);
            var auth = v.authorization;
            var runtime = new State();
            var result = RouteRequestManager.approveAuthorization(f.simulator, runtime, v);
            check(!result.accepted() && result.reason().equals("request_terminal"), "terminal approval rejected");
            check(v.request.getState() == state && v.authorization == auth, "rejection leaves authority untouched");
            v.authorization = null;
            result = RouteRequestManager.approveAuthorization(f.simulator, runtime, v);
            check(!result.accepted() && v.authorization == null && v.request.getState() == state, "terminal approval cannot recreate authority");
            v.authorization = auth;
            RouteAuthorizationLifecycle.releaseAll(f.simulator, v);
        }
    }

    private static void terminalOverride(RetainedProtectionRegression.Fixture f) {
        for (RequestState state : TERMINAL) {
            var v = vehicle(f, state);
            var auth = v.authorization;
            v.authorization = null;
            check(!RouteOverride.canGrantOneShotOverride(v), "terminal " + state + " cannot receive override");
            v.authorization = auth;
            RouteAuthorizationLifecycle.releaseAll(f.simulator, v);
        }
        var v = vehicle(f, RequestState.AUTHORIZED);
        var auth = v.authorization;
        v.authorization = null;
        check(RouteOverride.canGrantOneShotOverride(v), "live request remains eligible for target validation");
        v.authorization = auth;
    }

    private static void prematureCommands(RetainedProtectionRegression.Fixture f) {
        var v = new VehicleState();
        v.request = new RouteRequest(1, "path", 1, 1, List.of(), List.of(), List.of());
        for (RequestState state : List.of(RequestState.NONE, RequestState.APPROACHING)) {
            v.request.transitionTo(state, "fixture");
            var result = RouteAuthorizationLifecycle.revokeAuthorization(f.simulator, v);
            check(!result.accepted() && result.reason().equals("request_state"), "premature revocation rejected");
            result = RouteRequestManager.approveAuthorization(f.simulator, new State(), v);
            check(!result.accepted() && result.reason().equals("request_state"), "premature approval rejected");
            check(v.request.getState() == state, "rejection preserves pre-request state");
        }
    }

    private static void staleApproval(RetainedProtectionRegression.Fixture f) {
        var v = vehicle(f, RequestState.AUTHORIZED);
        var auth = v.authorization;
        var result = RouteRequestManager.approveAuthorization(f.simulator, new State(), v);
        check(!result.accepted() && result.reason().equals("topology_changed"), "stale topology cannot extend authority");
        check(v.authorization == auth && v.request.getState() == RequestState.AUTHORIZED, "stale approval does not partially mutate");
    }

    private static void manualApproval(RetainedProtectionRegression.Fixture f) {
        var v = vehicle(f, RequestState.AUTHORIZED);
        RouteAuthorizationLifecycle.releaseAll(f.simulator, v);
        v.request.transitionTo(RequestState.CHECKING, "fixture");
        v.request.transitionTo(RequestState.DENIED, "fixture");
        var rail = f.simulator.rails.stream().filter(item -> item.getHexId().equals(f.first)).findFirst().orElseThrow();
        f.simulator.railIdMap.put(f.first, rail);
        // Describe the real fixture rail, so approval exercises its production topology check.
        var fingerprint = rail.getHexId() + ":" + rail.getTransportMode() + ":" + rail.getStyles() + ":"
                + rail.getSpeedLimitKilometersPerHour(true) + ":" + rail.getSpeedLimitKilometersPerHour(false)
                + ":" + rail.isPlatform() + ":" + rail.isSiding() + ":" + rail.canTurnBack();
        v.path = new PathSnapshot(List.of(new PathSnapshot.PathSection(f.first, 0, 10, fingerprint,
                new BlockPos(0, -60, 0), new BlockPos(10, -60, 0), 0, false, 0, false, false, 0, false)), "path");
        check(v.path.matchesTopology(f.simulator), "real fixture rail matches topology");
        v.authorizationLookaheadEndDistance = 10;
        var runtime = new State();
        var result = RouteRequestManager.approveAuthorization(f.simulator, runtime, v);
        check(!result.accepted() && v.request.getState() == RequestState.DENIED && v.authorization == null,
                "denied request can be checked again and denied without an illegal transition");
        var entry = new SignalFace("entry", BlockPos.ZERO, new BlockPos(0, -60, 0), false, 0);
        ServerAspectManager.publishFaces(f.simulator.dimension, Map.of(entry.id(), entry), 1);
        var faces = v.path.getFaceTraversals(f.simulator.dimension, ServerAspectManager.getFaceSnapshot(f.simulator.dimension));
        var definition = RouteProjection.define(f.simulator, v.path, faces, faces.get(0));
        var mapping = new RouteRequestManager.GeneratedProtection(definition.blockDefinitionId(), definition.sectionIds(), definition.boundary().id());
        new org.mtrbr.data.SignalBlockSavedData().replaceGeneratedMappings(f.simulator.dimension,
                Map.of(entry.id(), mapping), Map.of(org.mtrbr.data.SignalBlockSavedData.occurrenceKey(v.path.getFingerprint(), faces.get(0).key()), mapping));
        result = RouteRequestManager.approveAuthorization(f.simulator, runtime, v);
        check(result.accepted() && v.request.getState() == RequestState.AUTHORIZED && v.authorization != null,
                "denied request gains real protected authority through checking and waiting");
        check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, List.of(mapping.blockId()), v.request.getRequestId()),
                "manual approval actually reserves and locks the physical block");
    }

    private static void liveRevoke(RetainedProtectionRegression.Fixture f) {
        var v = vehicle(f, RequestState.AUTHORIZED);
        var result = RouteAuthorizationLifecycle.revokeAuthorization(f.simulator, v);
        check(result.accepted() && v.request.getState() == RequestState.REVOKED, "live request is revoked");
        check(v.authorization == null && !v.retainedProtection.isEmpty(), "revocation preserves pending physical protection");
        check(JunctionStateManager.conflicts(f.simulator, List.of("junction-node|shared"), "other"), "occupied junction remains locked");
    }

    private static void ownerRelease(RetainedProtectionRegression.Fixture f) {
        JunctionStateManager.registerOwner(f.simulator, "owner", 1);
        check(JunctionStateManager.reserve(f.simulator, List.of("a", "b"), "owner"), "reserve");
        check(JunctionStateManager.lock(f.simulator, List.of("a", "b"), "owner"), "lock");
        JunctionStateManager.release(f.simulator, List.of("a"), "owner");
        check(JunctionStateManager.ownerVehicle(f.simulator, "owner") == 1, "owner metadata remains for held resource");
        JunctionStateManager.release(f.simulator, List.of("b"), "owner");
        check(JunctionStateManager.ownerVehicle(f.simulator, "owner") == Long.MIN_VALUE, "last release reclaims metadata");
    }

    private static void ownerTransfer(RetainedProtectionRegression.Fixture f) {
        JunctionStateManager.registerOwner(f.simulator, "old", 1);
        check(JunctionStateManager.reserve(f.simulator, List.of("a"), "old"), "reserve");
        check(JunctionStateManager.lock(f.simulator, List.of("a"), "old"), "lock");
        JunctionStateManager.transferRequestOwner(f.simulator, "old", "new", 1);
        check(JunctionStateManager.ownerVehicle(f.simulator, "old") == Long.MIN_VALUE, "handover reclaims old metadata");
        check(JunctionStateManager.ownerVehicle(f.simulator, "new") == 1, "handover preserves new metadata");
        JunctionStateManager.release(f.simulator, List.of("a"), "old");
        check(JunctionStateManager.areResourcesReservedAndLockedBy(f.simulator, List.of("a"), "new"), "old owner cannot unlock new locks");
        check(JunctionStateManager.ownerVehicle(f.simulator, "new") == 1, "old release cannot remove new metadata");
        JunctionStateManager.release(f.simulator, List.of("a"), "new");
        check(JunctionStateManager.ownerVehicle(f.simulator, "new") == Long.MIN_VALUE, "new metadata released with final lock");
    }

    private static void ownerSweep(RetainedProtectionRegression.Fixture f) {
        JunctionStateManager.registerOwner(f.simulator, "orphan", 2);
        JunctionStateManager.registerOwner(f.simulator, "pending", 1);
        check(JunctionStateManager.reserve(f.simulator, List.of("a"), "pending"), "reserve pending");
        check(JunctionStateManager.lock(f.simulator, List.of("a"), "pending"), "lock pending");
        JunctionStateManager.releaseStale(f.simulator, Set.of(), Map.of("a", Set.of("pending")));
        check(JunctionStateManager.ownerVehicle(f.simulator, "orphan") == Long.MIN_VALUE, "orphan metadata removed");
        check(JunctionStateManager.ownerVehicle(f.simulator, "pending") == 1, "pending owner remains");
        JunctionStateManager.releaseStale(f.simulator, Set.of());
        check(JunctionStateManager.ownerVehicle(f.simulator, "pending") == Long.MIN_VALUE, "stale owner reclaimed with resource");
    }

    private static void ownerFailure(RetainedProtectionRegression.Fixture f) {
        JunctionStateManager.registerOwner(f.simulator, "empty", 2);
        check(JunctionStateManager.reserve(f.simulator, List.of(), "empty"), "empty acquisition succeeds");
        check(JunctionStateManager.ownerVehicle(f.simulator, "empty") == Long.MIN_VALUE, "empty acquisition does not retain metadata");
        JunctionStateManager.registerOwner(f.simulator, "held", 1);
        check(JunctionStateManager.reserve(f.simulator, List.of("a"), "held"), "hold conflict");
        JunctionStateManager.registerOwner(f.simulator, "denied", 2);
        check(!JunctionStateManager.reserve(f.simulator, List.of("b", "a"), "denied"), "reject conflict");
        check(JunctionStateManager.ownerVehicle(f.simulator, "denied") == Long.MIN_VALUE, "failed acquisition reclaims metadata");
        check(JunctionStateManager.ownerVehicle(f.simulator, "held") == 1, "conflicting owner remains intact");
    }

    private static void auditBound(RetainedProtectionRegression.Fixture f) {
        var state = new State();
        for (int i = 0; i < 1024; i++) state.audit.add("event-" + i);
        RouteDispatchSnapshots.publishAuthorizations(f.simulator, state);
        var published = RouteRequestManager.getAudit(f.simulator);
        check(published.size() == 256 && published.get(0).equals("event-768") && published.get(255).equals("event-1023"), "bounded history preserves the most recent events in order");
    }

    private static void auditSnapshots(RetainedProtectionRegression.Fixture f) {
        var state = new State();
        state.audit.add("before");
        RouteDispatchSnapshots.publishAuthorizations(f.simulator, state);
        var first = RouteRequestManager.getAudit(f.simulator);
        RouteDispatchSnapshots.publishAuthorizations(f.simulator, state);
        check(first == RouteRequestManager.getAudit(f.simulator), "unchanged history reuses immutable snapshot");
        state.audit.add("after");
        RouteDispatchSnapshots.publishAuthorizations(f.simulator, state);
        check(first.equals(List.of("before")), "older snapshots remain frozen");
        check(RouteRequestManager.getAudit(f.simulator).equals(List.of("before", "after")), "new snapshot contains new event");
        try { first.add("invalid"); throw new AssertionError("published history is mutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void run(String name, Consumer<RetainedProtectionRegression.Fixture> test) {
        RouteRequestManager.resetAll(); SectionStateManager.resetAll(); CapacityLeaseManager.resetAll();
        cases++;
        try {
            test.accept(new RetainedProtectionRegression.Fixture("review-" + cases));
            System.out.println("PASS: " + name);
        } catch (Exception | AssertionError failure) {
            failures++;
            failure.printStackTrace();
            System.err.println("FAIL: " + name + " -> " + failure);
        } finally {
            RouteRequestManager.resetAll(); SectionStateManager.resetAll(); CapacityLeaseManager.resetAll();
        }
    }

    /** Supplies the accessors normally woven into Vehicle by the game runtime. */
    private static final class TestVehicle extends Vehicle implements org.mtrbr.mixin.VehicleAccess {
        private double progress, speed;
        TestVehicle() { super(new JsonReader(new JsonObject())); }
        public double mtrbr$getRailProgress() { return progress; }
        public void mtrbr$setRailProgress(double value) { progress = value; }
        public double mtrbr$getSpeed() { return speed; }
        public void mtrbr$setSpeed(double value) { speed = value; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
