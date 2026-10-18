package org.mtrbr.server;

import net.minecraftforge.fml.loading.FMLPaths;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contract tests against real resource managers, without private-state reflection. */
public final class RetainedProtectionRegression {
    private static final String OWNER = "protection-owner";
    private static int passed;

    public static void main(String[] args) {
        FMLPaths.loadAbsolutePaths(Path.of(".").toAbsolutePath().normalize());
        run("whole block remains protected until every occupied section clears", RetainedProtectionRegression::wholeBlock);
        run("shared keys outlive the first cleared occurrence", RetainedProtectionRegression::sharedKeys);
        run("last of several occurrences releases shared keys in one pass", RetainedProtectionRegression::allCleared);
        run("active authorization keeps its sections, block and junction", RetainedProtectionRegression::activeProtection);
        run("handover survives stale cleanup and releases with the new owner", RetainedProtectionRegression::handover);
        run("recorded owner cannot release another request's locks", RetainedProtectionRegression::ownerIsolation);
        run("confirmed removal releases protection despite occupancy", RetainedProtectionRegression::confirmedRemoval);
        System.out.println("Retained protection regression: " + passed + " cases passed.");
    }

    private static void wholeBlock(Fixture f) {
        var block = f.block("whole", 0, List.of(f.first, f.second));
        f.hold(block, OWNER);
        f.protection.retain(OWNER, block, ReleaseReason.INVALID);
        var result = f.protection.releaseCleared(f.simulator, Set.of(f.second), List.of());
        check(result.blocks().isEmpty() && result.sections().isEmpty(), "partial occupancy retains whole block");
        check(!SectionStateManager.areSectionsAvailable(f.simulator, List.of(f.first), "other", 99, false), "clear prefix remains locked");
        check(JunctionStateManager.conflicts(f.simulator, block.junctionMovementIds(), "other"), "junction remains locked");
        result = f.protection.releaseCleared(f.simulator, Set.of(), List.of());
        check(result.blocks().size() == 1 && result.sections().size() == 2, "all resource types clear together");
        f.available(block);
        check(f.protection.isEmpty(), "no references left");
        check(f.protection.releaseCleared(f.simulator, Set.of(), List.of()).blocks().isEmpty(), "repeated cleanup is harmless");
    }

    private static void sharedKeys(Fixture f) {
        var first = f.block("shared", 0, List.of(f.first));
        var second = f.block("shared", 1, List.of(f.second));
        f.hold(first, OWNER); f.hold(second, OWNER);
        f.protection.retain(OWNER, first, ReleaseReason.TURNBACK);
        f.protection.retain(OWNER, second, ReleaseReason.TURNBACK);
        f.protection.releaseCleared(f.simulator, Set.of(f.second), List.of());
        check(SectionStateManager.areBlocksAvailable(f.simulator, List.of(first.occurrenceId()), "other"), "cleared occurrence released");
        check(!SectionStateManager.areBlocksAvailable(f.simulator, List.of(first.blockId()), "other"), "canonical block retained");
        check(JunctionStateManager.conflicts(f.simulator, second.junctionMovementIds(), "other"), "shared junction retained");
        f.protection.releaseCleared(f.simulator, Set.of(), List.of());
        f.available(first); f.available(second);
    }

    private static void allCleared(Fixture f) {
        var first = f.block("together", 0, List.of(f.first));
        var second = f.block("together", 1, List.of(f.second));
        f.hold(first, OWNER); f.hold(second, OWNER);
        f.protection.retain(OWNER, first, ReleaseReason.REVOKED);
        f.protection.retain(OWNER, second, ReleaseReason.REVOKED);
        check(f.protection.releaseCleared(f.simulator, Set.of(), List.of()).blocks().size() == 2, "both cleared in one pass");
        check(f.protection.isEmpty(), "all references removed in one pass");
        f.available(first); f.available(second);
    }

    private static void activeProtection(Fixture f) {
        var pending = f.block("active", 0, List.of(f.first));
        var active = f.block("active", 1, List.of(f.first));
        f.hold(pending, OWNER); f.hold(active, OWNER);
        f.protection.retain(OWNER, pending, ReleaseReason.INVALID);
        var result = f.protection.releaseCleared(f.simulator, Set.of(), List.of(active));
        check(result.blocks().size() == 1 && result.sections().isEmpty(), "active section is not released");
        check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, List.of(active.blockId(), active.occurrenceId()), OWNER), "active block still locked");
        check(JunctionStateManager.areResourcesReservedAndLockedBy(f.simulator, active.junctionMovementIds(), OWNER), "active junction still locked");
        check(f.protection.releasableBlockLockIds(List.of(active.blockId(), "unused", "unused"), List.of(active)).equals(List.of("unused")), "release candidates exclude active keys and duplicates");
    }

    private static void handover(Fixture f) {
        var block = f.block("handover", 0, List.of(f.first, f.second));
        f.hold(block, OWNER);
        f.protection.retain(OWNER, block, ReleaseReason.INVALID);
        SectionStateManager.transferRequestOwner(f.simulator, OWNER, "new-owner");
        JunctionStateManager.transferRequestOwner(f.simulator, OWNER, "new-owner", 1);
        f.protection.transferOwner(OWNER, "new-owner");
        Map<String, Set<String>> sections = new HashMap<>(), blocks = new HashMap<>(), junctions = new HashMap<>();
        f.protection.retainOwners(sections, blocks, junctions);
        check(sections.get(f.first).equals(Set.of("new-owner")), "section owner updated");
        check(blocks.get(block.blockId()).equals(Set.of("new-owner")), "canonical owner updated");
        check(blocks.get(block.occurrenceId()).equals(Set.of("new-owner")), "occurrence owner updated");
        check(junctions.get(block.junctionMovementIds().get(0)).equals(Set.of("new-owner")), "junction owner updated");
        SectionStateManager.releaseStaleReservations(f.simulator, Set.of(), sections, blocks);
        JunctionStateManager.releaseStale(f.simulator, Set.of(), junctions);
        f.protection.releaseCleared(f.simulator, Set.of(f.second), List.of());
        check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, RetainedProtection.blockLockIds(List.of(block)), "new-owner"), "pending owner survives stale sweep");
        f.protection.releaseCleared(f.simulator, Set.of(), List.of());
        f.available(block);
    }

    private static void ownerIsolation(Fixture f) {
        var block = f.block("owner", 0, List.of(f.first));
        f.hold(block, "different-owner");
        f.protection.retain(OWNER, block, ReleaseReason.INVALID);
        f.protection.releaseCleared(f.simulator, Set.of(), List.of());
        check(SectionStateManager.areBlocksReservedAndLockedBy(f.simulator, RetainedProtection.blockLockIds(List.of(block)), "different-owner"), "block release must match owner");
        check(JunctionStateManager.areResourcesReservedAndLockedBy(f.simulator, block.junctionMovementIds(), "different-owner"), "junction release must match owner");
        check(SectionStateManager.getSections(f.simulator).get(f.first).lockedBy.contains("different-owner"), "section release must match owner");
    }

    private static void confirmedRemoval(Fixture f) {
        var block = f.block("removed", 0, List.of(f.first));
        f.hold(block, OWNER);
        f.protection.retain(OWNER, block, ReleaseReason.CANCELED);
        f.protection.releaseAll(f.simulator);
        check(f.protection.isEmpty(), "removal clears all references");
        f.available(block);
    }

    private static void run(String name, java.util.function.Consumer<Fixture> test) {
        SectionStateManager.resetAll(); JunctionStateManager.resetAll();
        try {
            test.accept(new Fixture("protection-" + passed));
            passed++;
            System.out.println("PASS: " + name);
        } finally {
            SectionStateManager.resetAll(); JunctionStateManager.resetAll();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static final class Fixture {
        final Simulator simulator;
        final RetainedProtection protection = new RetainedProtection();
        final String first, second;

        Fixture(String name) {
            simulator = new Simulator(name, new String[] { name }, Path.of("simulation", name), false);
            Rail a = rail(0), b = rail(10);
            simulator.rails.add(a); simulator.rails.add(b);
            first = a.getHexId(); second = b.getHexId();
            SectionStateManager.beginSimulation(simulator);
        }

        private static Rail rail(int x) {
            return Rail.newRail(new Position(x, -60, 0), Angle.E, new Position(x + 10, -60, 0), Angle.W,
                    Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 80, false, false, false, false, false, TransportMode.TRAIN);
        }

        Authorization.BlockAuthorization block(String id, int occurrence, List<String> sections) {
            return new Authorization.BlockAuthorization(id, occurrence, 0, 10, sections, List.of(), List.of(), true,
                    "end", 10, null, null, "path", sections, List.of("junction-node|shared"));
        }

        void hold(Authorization.BlockAuthorization block, String owner) {
            var ids = RetainedProtection.blockLockIds(List.of(block));
            check(SectionStateManager.reserveSections(simulator, block.sectionIds(), owner, 1, false), "reserve sections");
            check(SectionStateManager.lockSections(simulator, block.sectionIds(), owner), "lock sections");
            check(SectionStateManager.reserveBlocks(simulator, ids, owner), "reserve blocks");
            check(SectionStateManager.lockBlocks(simulator, ids, owner), "lock blocks");
            JunctionStateManager.registerOwner(simulator, owner, 1);
            check(JunctionStateManager.reserve(simulator, block.junctionMovementIds(), owner), "reserve junction");
            check(JunctionStateManager.lock(simulator, block.junctionMovementIds(), owner), "lock junction");
        }

        void available(Authorization.BlockAuthorization block) {
            check(SectionStateManager.areBlocksAvailable(simulator, RetainedProtection.blockLockIds(List.of(block)), "other"), "blocks released");
            check(SectionStateManager.areSectionsAvailable(simulator, block.sectionIds(), "other", 99, false), "sections released");
            check(!JunctionStateManager.conflicts(simulator, block.junctionMovementIds(), "other"), "junction released");
        }
    }
}
