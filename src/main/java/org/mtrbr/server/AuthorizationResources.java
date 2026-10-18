package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * One simulation-thread attempt to acquire a validated authorization's resources.
 * A failed attempt releases only resources this request did not already own.
 * Path projection, conflict diagnostics and request transitions belong to the caller.
 */
record AuthorizationResources(String requestId, long vehicleId, List<String> sectionIds,
        List<String> blockIds, List<String> junctionResources, List<String> zoneIds) {
    AuthorizationResources {
        sectionIds = List.copyOf(sectionIds);
        blockIds = List.copyOf(blockIds);
        junctionResources = List.copyOf(junctionResources);
        zoneIds = List.copyOf(zoneIds);
    }

    boolean reserveAndLock(Simulator simulator) {
        JunctionStateManager.registerOwner(simulator, requestId, vehicleId);
        List<String> newBlocks = SectionStateManager.unownedBlocks(simulator, blockIds, requestId);
        Map<String, SectionStateManager.SectionSnapshot> before = SectionStateManager.getSections(simulator, sectionIds);
        List<String> newSections = sectionIds.stream().filter(id -> !before.containsKey(id)
                || (!before.get(id).reservedBy.contains(requestId) && !before.get(id).lockedBy.contains(requestId))).toList();
        List<String> newJunctions = JunctionStateManager.unownedResources(simulator, junctionResources, requestId);
        List<String> newZones = CapacityLeaseManager.unownedZones(simulator, zoneIds, requestId);
        boolean blocksReserved = false;
        boolean sectionsReserved = false;
        boolean zonesReserved = false;
        boolean completed = false;
        try {
            if (!CapacityLeaseManager.reserveZones(simulator, zoneIds, requestId, vehicleId)) return false;
            zonesReserved = true;
            if (!SectionStateManager.reserveBlocks(simulator, blockIds, requestId)) return false;
            blocksReserved = true;
            if (!SectionStateManager.reserveSections(simulator, sectionIds, requestId, vehicleId, false)) return false;
            sectionsReserved = true;
            if (!SectionStateManager.lockSections(simulator, sectionIds, requestId)) return false;
            if (!SectionStateManager.lockBlocks(simulator, blockIds, requestId)) return false;
            if (!JunctionStateManager.reserve(simulator, junctionResources, requestId)) return false;
            if (!JunctionStateManager.lock(simulator, junctionResources, requestId)) return false;
            if (!CapacityLeaseManager.lockZones(simulator, zoneIds, requestId)) return false;
            completed = true;
            return true;
        } finally {
            if (!completed) {
                JunctionStateManager.release(simulator, newJunctions, requestId);
                if (sectionsReserved) SectionStateManager.releaseSections(simulator, newSections, requestId);
                if (blocksReserved) SectionStateManager.releaseBlocks(simulator, newBlocks, requestId);
                if (zonesReserved) CapacityLeaseManager.releaseReservedZones(simulator, newZones, requestId);
            }
        }
    }
}
