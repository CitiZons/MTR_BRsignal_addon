package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Physical protection left behind when movement authority is retired.
 * Owned by one vehicle on the simulation thread, independently of its current request.
 * A block protects all its sections until none are occupied. Shared keys stay locked
 * until the last pending or active occurrence lets go; releases use the recorded owner.
 */
final class RetainedProtection {
    private final Map<String, Section> sections = new HashMap<>();
    private final Map<String, Block> blocks = new HashMap<>();

    boolean isEmpty() {
        return sections.isEmpty() && blocks.isEmpty();
    }

    boolean protectsSection(String id) {
        return sections.containsKey(id);
    }

    List<Block> blocks() {
        return List.copyOf(blocks.values());
    }

    Set<String> blockSections() {
        Set<String> ids = new HashSet<>();
        blocks.values().forEach(block -> ids.addAll(block.sectionIds()));
        return ids;
    }

    void retainSections(Collection<String> ids, String owner, ReleaseReason reason) {
        ids.forEach(id -> sections.put(id, new Section(owner, reason)));
    }

    Block retain(String owner, Authorization.BlockAuthorization authorization, ReleaseReason reason) {
        Block block = Block.from(owner, authorization);
        blocks.put(block.occurrenceId(), block);
        retainSections(block.sectionIds(), owner, reason);
        return block;
    }

    void transferOwner(String from, String to) {
        sections.replaceAll((id, section) -> section.owner().equals(from)
                ? new Section(to, section.reason()) : section);
        blocks.replaceAll((id, block) -> block.owner().equals(from) ? block.withOwner(to) : block);
    }

    void retainOwners(Map<String, Set<String>> sectionOwners, Map<String, Set<String>> blockOwners,
            Map<String, Set<String>> junctionOwners) {
        sections.forEach((id, section) -> addOwner(sectionOwners, id, section.owner()));
        for (Block block : blocks.values()) {
            block.sectionIds().forEach(id -> addOwner(sectionOwners, id, block.owner()));
            addOwner(blockOwners, block.occurrenceId(), block.owner());
            addOwner(blockOwners, block.blockId(), block.owner());
            block.junctionResources().forEach(id -> addOwner(junctionOwners, id, block.owner()));
        }
    }

    private static void addOwner(Map<String, Set<String>> owners, String id, String owner) {
        owners.computeIfAbsent(id, ignored -> new HashSet<>()).add(owner);
    }

    static List<String> blockLockIds(List<Authorization.BlockAuthorization> authorizations) {
        Set<String> ids = new LinkedHashSet<>();
        for (Authorization.BlockAuthorization block : authorizations) {
            ids.add(block.blockId());
            ids.add(block.occurrenceId());
        }
        return List.copyOf(ids);
    }

    List<String> releasableBlockLockIds(List<String> candidates, List<Authorization.BlockAuthorization> active) {
        return releasableBlockLockIds(candidates, active, blocks.values());
    }

    private static List<String> releasableBlockLockIds(List<String> candidates,
            List<Authorization.BlockAuthorization> active, Collection<Block> pending) {
        Set<String> held = new HashSet<>(blockLockIds(active));
        pending.forEach(block -> { held.add(block.blockId()); held.add(block.occurrenceId()); });
        return candidates.stream().filter(id -> !held.contains(id)).distinct().toList();
    }

    List<String> releasableJunctionResources(List<String> candidates, List<Authorization.BlockAuthorization> active) {
        return releasableJunctionResources(candidates, active, blocks.values());
    }

    private static List<String> releasableJunctionResources(List<String> candidates,
            List<Authorization.BlockAuthorization> active, Collection<Block> pending) {
        Set<String> held = new HashSet<>();
        active.forEach(block -> held.addAll(block.junctionMovementIds()));
        pending.forEach(block -> held.addAll(block.junctionResources()));
        return candidates.stream().filter(id -> !held.contains(id)).distinct().toList();
    }

    /** Distance and request state cannot prove physical clearance. */
    Release releaseCleared(Simulator simulator, Set<String> occupied, List<Authorization.BlockAuthorization> active) {
        List<Block> releasedBlocks = new ArrayList<>();
        for (Block block : blocks()) {
            if (block.sectionIds().stream().anyMatch(occupied::contains)) continue;
            List<Block> others = blocks.values().stream().filter(other -> other != block).toList();
            SectionStateManager.releaseBlocks(simulator,
                    releasableBlockLockIds(List.of(block.occurrenceId(), block.blockId()), active, others), block.owner());
            JunctionStateManager.release(simulator,
                    releasableJunctionResources(block.junctionResources(), active, others), block.owner());
            // Remove now, so the final cleared occurrence also releases the canonical key.
            blocks.remove(block.occurrenceId());
            releasedBlocks.add(block);
        }
        Set<String> heldSections = blockSections();
        heldSections.addAll(occupied);
        active.forEach(block -> heldSections.addAll(block.sectionIds()));
        List<String> releasedSections = sections.keySet().stream().filter(id -> !heldSections.contains(id)).toList();
        for (String id : releasedSections) {
            SectionStateManager.releaseSections(simulator, List.of(id), sections.get(id).owner());
            sections.remove(id);
        }
        return new Release(releasedBlocks, releasedSections);
    }

    /** Only for confirmed removal; ordinary invalidation must wait for physical clearance. */
    void releaseAll(Simulator simulator) {
        sections.forEach((id, section) -> SectionStateManager.releaseSections(simulator, List.of(id), section.owner()));
        blocks.values().forEach(block -> {
            SectionStateManager.releaseBlocks(simulator, List.of(block.occurrenceId(), block.blockId()), block.owner());
            JunctionStateManager.release(simulator, block.junctionResources(), block.owner());
        });
        sections.clear();
        blocks.clear();
    }

    @Override
    public String toString() {
        return "sections=" + sections + " blocks=" + blocks;
    }

    private record Section(String owner, ReleaseReason reason) {}

    record Release(List<Block> blocks, List<String> sections) {
        Release {
            blocks = List.copyOf(blocks);
            sections = List.copyOf(sections);
        }
    }

    record Block(String owner, String occurrenceId, String blockId, double startDistance, double endDistance,
            List<String> sectionIds, List<String> junctionResources, PathSnapshot.FaceTraversalKey entryFaceKey) {
        Block {
            sectionIds = List.copyOf(sectionIds);
            junctionResources = List.copyOf(junctionResources);
        }

        static Block from(String owner, Authorization.BlockAuthorization block) {
            return new Block(owner, block.occurrenceId(), block.blockId(), block.startDistance(), block.endDistance(),
                    block.sectionIds(), block.junctionMovementIds(), block.entryFaceTraversalKey());
        }

        private Block withOwner(String owner) {
            return new Block(owner, occurrenceId, blockId, startDistance, endDistance, sectionIds, junctionResources, entryFaceKey);
        }
    }
}
