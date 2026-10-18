package org.mtrbr.server;

import java.util.List;
import java.util.Map;

import org.mtrbr.data.SignalBlockSavedData;

/** Behavioral contracts for extracted interlocking components, run by Gradle check. */
public final class InterlockingRegression {
    private static int acquisitionCases;
    public static void main(String[] args) {
        RetainedProtectionRegression.main(args);
        handoff();
        acquisition("successful acquisition locks every resource", InterlockingRegression::success);
        acquisition("late junction failure rolls back only new resources", InterlockingRegression::lateFailure);
        acquisition("section failure rolls back new blocks", InterlockingRegression::sectionFailure);
        acquisition("block conflict preserves another owner's lock", InterlockingRegression::blockConflict);
        acquisition("missing zone fails before acquiring resources", InterlockingRegression::missingZone);
        acquisition("late failure releases new zones and preserves earlier zone locks", InterlockingRegression::zoneRollback);
        System.out.println("Authorization resource regression: 6 cases passed.");
        vehicleCodes();
    }

    private static void vehicleCodes() {
        RouteVehicleCodes.reset();
        try {
            // Simulation snapshots and server/Web actions can request the same code concurrently.
            var allocations = java.util.stream.IntStream.range(0, 512).parallel()
                    .mapToObj(i -> Map.entry((long)(i % 64), RouteVehicleCodes.getVehicleCode(i % 64))).toList();
            Map<Long, String> byVehicle = new java.util.HashMap<>();
            for (var allocation : allocations) {
                String previous = byVehicle.putIfAbsent(allocation.getKey(), allocation.getValue());
                check(previous == null || previous.equals(allocation.getValue()), "one stable code per vehicle across callers");
            }
            check(byVehicle.size() == 64 && new java.util.HashSet<>(byVehicle.values()).size() == 64, "unique codes across vehicles");
            byVehicle.forEach((id, code) -> {
                check(id.equals(RouteVehicleCodes.resolveVehicleCode(code)), "allocation and reverse lookup agree");
                RouteVehicleCodes.releaseVehicleCode(id);
                check(RouteVehicleCodes.resolveVehicleCode(code) == null, "release removes reverse lookup");
            });
            System.out.println("PASS: concurrent server/simulation vehicle-code allocation and release");
        } finally {
            RouteVehicleCodes.reset();
        }
    }

    private static void handoff() {
        var dwell = new TurnbackHandoff();
        check(!dwell.active() && !dwell.awaitingActivity(), "normal state does not defer gate");
        check(dwell.begin(100), "begin native handoff");
        check(!dwell.begin(1100), "repeated observation must not restart native timeout");
        check(dwell.checkTimeout(1299) == null, "native dwell has its own longer deadline");
        var nativeTimeout = dwell.checkTimeout(1300);
        check(nativeTimeout != null && nativeTimeout.phase() == TurnbackHandoff.Phase.NATIVE_TERMINATING
                && nativeTimeout.elapsedTicks() == 1200, "native timeout fires at boundary");
        check(!dwell.active() && !dwell.awaitingActivity(), "timeout resumes gate");
        check(dwell.checkTimeout(1400) == null && !dwell.begin(1400), "failed handoff does not repeat or restart");
        System.out.println("PASS: native dwell timeout starts once and fails closed");

        var reversal = new TurnbackHandoff();
        reversal.begin(100);
        reversal.reversalObserved(1200);
        check(reversal.awaitingActivity() && reversal.reacquirePending(), "reversal awaits new-direction activity");
        check(reversal.checkTimeout(1399) == null, "reacquisition clock starts after reversal, excluding dwell");
        var newActivityTimeout = reversal.checkTimeout(1400);
        check(newActivityTimeout != null && newActivityTimeout.phase() == TurnbackHandoff.Phase.REACQUIRING
                && newActivityTimeout.elapsedTicks() == 200, "new activity timeout at its own boundary");
        check(!reversal.active() && !reversal.awaitingActivity(), "new activity timeout restores gate");
        System.out.println("PASS: reversal starts an independent reacquisition deadline");

        var complete = new TurnbackHandoff();
        complete.begin(0); complete.reversalObserved(10); complete.activityReady(); complete.grantReported();
        check(complete.phase() == TurnbackHandoff.Phase.NORMAL && !complete.active()
                && !complete.awaitingActivity() && !complete.reacquirePending(), "successful handoff returns to normal");
        check(complete.checkTimeout(5000) == null && complete.begin(5000), "next reversal can begin with a fresh clock");
        check(complete.checkTimeout(6199) == null, "previous deadline discarded");
        System.out.println("PASS: completed handoff discards old deadlines and permits the next reversal");

        var invalidated = new TurnbackHandoff();
        invalidated.begin(0); invalidated.reversalObserved(10); invalidated.fail();
        check(!invalidated.active() && !invalidated.awaitingActivity(), "invalidation immediately restores gate");
        check(invalidated.reacquirePending(), "failure can still be reported until acquisition succeeds");
        invalidated.grantReported();
        check(!invalidated.reacquirePending(), "grant report finishes pending diagnostic");
        System.out.println("PASS: invalidation fails handoff without hiding pending acquisition diagnostics");
        System.out.println("Turnback handoff regression: 4 cases passed.");
    }

    private static AuthorizationResources resources(List<String> sections,
            List<String> blocks, List<String> junctions) {
        return new AuthorizationResources("transaction", 1, sections, blocks, junctions, List.of());
    }

    private static void success(RetainedProtectionRegression.Fixture f) {
        var block = f.block("success", 0, List.of(f.first, f.second));
        var plan = resources(block.sectionIds(), RetainedProtection.blockLockIds(List.of(block)), block.junctionMovementIds());
        check(plan.reserveAndLock(f.simulator), "acquisition succeeds");
        check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, plan.blockIds(), "transaction"), "all block keys locked");
        check(SectionStateManager.getSections(f.simulator).values().stream().allMatch(s -> s.lockedBy.contains("transaction")), "all sections locked");
        check(JunctionStateManager.areResourcesReservedAndLockedBy(f.simulator, plan.junctionResources(), "transaction"), "all junctions locked");
        check(plan.reserveAndLock(f.simulator), "same-owner reacquisition succeeds");
    }

    private static void lateFailure(RetainedProtectionRegression.Fixture f) {
        var previous = f.block("previous", 0, List.of(f.first));
        f.hold(previous, "transaction");
        JunctionStateManager.registerOwner(f.simulator, "other", 2);
        check(JunctionStateManager.reserve(f.simulator, List.of("junction-node|conflict"), "other"), "reserve conflict");
        check(JunctionStateManager.lock(f.simulator, List.of("junction-node|conflict"), "other"), "lock conflict");
        var plan = resources(List.of(f.first, f.second), List.of(previous.blockId(), previous.occurrenceId(), "new-block"),
                List.of(previous.junctionMovementIds().get(0), "junction-node|new", "junction-node|conflict"));
        check(!plan.reserveAndLock(f.simulator), "junction conflict rejects attempt after section and block locking");
        check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, RetainedProtection.blockLockIds(List.of(previous)), "transaction"), "previous block survives rollback");
        check(SectionStateManager.getSections(f.simulator).get(f.first).lockedBy.contains("transaction"), "previous section survives rollback");
        check(JunctionStateManager.areResourcesReservedAndLockedBy(f.simulator, previous.junctionMovementIds(), "transaction"), "previous junction survives rollback");
        check(SectionStateManager.areBlocksAvailable(f.simulator, List.of("new-block"), "other"), "new block rolled back");
        check(SectionStateManager.areSectionsAvailable(f.simulator, List.of(f.second), "other", 2, false), "new section rolled back");
        check(!JunctionStateManager.conflicts(f.simulator, List.of("junction-node|new"), "other"), "new junction leaves no lock");
        check(JunctionStateManager.areResourcesReservedAndLockedBy(f.simulator, List.of("junction-node|conflict"), "other"), "conflicting owner's junction untouched");
    }

    private static void sectionFailure(RetainedProtectionRegression.Fixture f) {
        check(SectionStateManager.reserveSections(f.simulator, List.of(f.second), "other", 2, false), "reserve conflict section");
        check(SectionStateManager.lockSections(f.simulator, List.of(f.second), "other"), "lock conflict section");
        check(!resources(List.of(f.first, f.second), List.of("new-block"), List.of()).reserveAndLock(f.simulator), "section conflict rejects acquisition");
        check(SectionStateManager.areBlocksAvailable(f.simulator, List.of("new-block"), "other"), "new block rolled back");
        check(SectionStateManager.areSectionsAvailable(f.simulator, List.of(f.first), "other", 2, false), "section before conflict left available");
        check(SectionStateManager.getSections(f.simulator).get(f.second).lockedBy.contains("other"), "other section remains locked");
    }

    private static void blockConflict(RetainedProtectionRegression.Fixture f) {
        check(SectionStateManager.reserveBlocks(f.simulator, List.of("conflict"), "other"), "reserve conflict block");
        check(SectionStateManager.lockBlocks(f.simulator, List.of("conflict"), "other"), "lock conflict block");
        check(!resources(List.of(f.first), List.of("new-block", "conflict"), List.of()).reserveAndLock(f.simulator), "block conflict rejects acquisition");
        check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, List.of("conflict"), "other"), "other block untouched");
        check(SectionStateManager.areBlocksAvailable(f.simulator, List.of("new-block"), "other"), "new block not retained");
        check(SectionStateManager.areSectionsAvailable(f.simulator, List.of(f.first), "other", 2, false), "sections not acquired");
    }

    private static void missingZone(RetainedProtectionRegression.Fixture f) {
        var plan = new AuthorizationResources("transaction", 1, List.of(f.first), List.of("new-block"), List.of(), List.of("missing"));
        check(!plan.reserveAndLock(f.simulator), "undefined zone fails closed");
        check(SectionStateManager.areBlocksAvailable(f.simulator, plan.blockIds(), "other"), "no block acquisition before zone rejection");
        check(SectionStateManager.areSectionsAvailable(f.simulator, plan.sectionIds(), "other", 2, false), "no section acquisition before zone rejection");
    }

    private static void zoneRollback(RetainedProtectionRegression.Fixture f) {
        var oldZone = new SignalBlockSavedData.SingleLineZoneDefinition("old-zone", List.of(f.first), 0, 0);
        var newZone = new SignalBlockSavedData.SingleLineZoneDefinition("new-zone", List.of(f.second), 0, 0);
        // Loading definitions is a public operation; no private lease state is manufactured.
        CapacityLeaseManager.getZoneIds(f.simulator, List.of(), new SignalBlockSavedData.Snapshot(
                Map.of(), Map.of(), Map.of(), Map.of(oldZone.zoneId(), oldZone, newZone.zoneId(), newZone)));
        check(CapacityLeaseManager.reserveZones(f.simulator, List.of(oldZone.zoneId()), "transaction", 1), "reserve prior zone");
        check(CapacityLeaseManager.lockZones(f.simulator, List.of(oldZone.zoneId()), "transaction"), "lock prior zone");
        JunctionStateManager.registerOwner(f.simulator, "other", 2);
        check(JunctionStateManager.reserve(f.simulator, List.of("junction-node|conflict"), "other"), "reserve late conflict");
        var plan = new AuthorizationResources("transaction", 1, List.of(f.second), List.of("new-block"),
                List.of("junction-node|conflict"), List.of(oldZone.zoneId(), newZone.zoneId()));
        check(!plan.reserveAndLock(f.simulator), "late conflict rejects attempt with zone reservations");
        check(!CapacityLeaseManager.areZonesAvailable(f.simulator, List.of(oldZone.zoneId()), "other"), "previous zone lock survives rollback");
        check(CapacityLeaseManager.areZonesAvailable(f.simulator, List.of(newZone.zoneId()), "other"), "new zone reservation rolled back");
        check(SectionStateManager.areBlocksAvailable(f.simulator, plan.blockIds(), "other"), "new block rolled back with zone");
        check(SectionStateManager.areSectionsAvailable(f.simulator, plan.sectionIds(), "other", 2, false), "new section rolled back with zone");
    }

    private static void acquisition(String name, java.util.function.Consumer<RetainedProtectionRegression.Fixture> test) {
        SectionStateManager.resetAll(); JunctionStateManager.resetAll(); CapacityLeaseManager.resetAll();
        try {
            test.accept(new RetainedProtectionRegression.Fixture("transaction-" + acquisitionCases++));
            System.out.println("PASS: " + name);
        } finally {
            SectionStateManager.resetAll(); JunctionStateManager.resetAll(); CapacityLeaseManager.resetAll();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
