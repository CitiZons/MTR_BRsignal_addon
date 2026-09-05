package org.mtrbr.server;

import net.minecraft.server.level.ServerLevel;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtrbr.data.SignalBlockSavedData;
import org.mtrbr.mixin.SidingAccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capacity constraints which are deliberately outside the normal protection
 * model: opposite-direction single-line zones and siding fleet limits.
 */
public final class CapacityLeaseManager {
	private static final Map<Simulator, State> STATES = Collections.synchronizedMap(new IdentityHashMap<>());

	private CapacityLeaseManager() {
	}

	/** Rebuilds only from MTR route topology; it is never called from a vehicle tick. */
	public static void rebuildSingleLineZones(Simulator simulator) {
		final State state = state(simulator);
		final List<List<DirectedSection>> paths = new ArrayList<>();
		for (final Siding siding : simulator.sidings) {
			final SidingAccess access = (SidingAccess) (Object) siding;
			final List<PathData> route = new ArrayList<>();
			route.addAll(access.mtrbr$getPathSidingToMainRoute());
			route.addAll(access.mtrbr$getPathMainRoute());
			route.addAll(access.mtrbr$getPathMainRouteToSiding());
			final List<DirectedSection> sections = directedSections(route);
			if (!sections.isEmpty()) paths.add(sections);
		}
		if (paths.isEmpty()) {
			MtrbrDebugLog.event("MTRBR-SINGLE-LINE-ZONE", "action=REBUILD_SKIPPED reason=no-resolved-siding-paths");
			return;
		}

		final Map<String, Set<String>> directions = new HashMap<>();
		for (final List<DirectedSection> path : paths) {
			for (final DirectedSection section : path) {
				directions.computeIfAbsent(section.sectionId, ignored -> new HashSet<>()).add(section.start + ">" + section.end);
			}
		}
		final Set<String> bidirectional = new HashSet<>();
		for (final Map.Entry<String, Set<String>> entry : directions.entrySet()) {
			for (final String direction : entry.getValue()) {
				final int separator = direction.indexOf('>');
				if (separator > 0 && entry.getValue().contains(direction.substring(separator + 1) + ">" + direction.substring(0, separator))) {
					bidirectional.add(entry.getKey());
					break;
				}
			}
		}
		final DisjointSet components = new DisjointSet(bidirectional);
		for (final List<DirectedSection> path : paths) {
			String previous = null;
			for (final DirectedSection section : path) {
				if (!bidirectional.contains(section.sectionId)) {
					previous = null;
					continue;
				}
				if (previous != null) components.union(previous, section.sectionId);
				previous = section.sectionId;
			}
		}
		final Map<String, Set<String>> grouped = new HashMap<>();
		for (final String sectionId : bidirectional) {
			grouped.computeIfAbsent(components.find(sectionId), ignored -> new LinkedHashSet<>()).add(sectionId);
		}
		final Map<String, SignalBlockSavedData.SingleLineZoneDefinition> rebuilt = new HashMap<>();
		final long topologyRevision = SectionStateManager.getTopologyRevision(simulator);
		final long routeRevision = ++state.routeRevision;
		for (final Set<String> sectionIds : grouped.values()) {
			final List<String> ordered = sectionIds.stream().sorted().toList();
			final String zoneId = "single-line:" + String.join("|", ordered);
			rebuilt.put(zoneId, new SignalBlockSavedData.SingleLineZoneDefinition(zoneId, ordered, topologyRevision, routeRevision));
		}
		state.zones = Map.copyOf(rebuilt);
		state.zonesPendingPersistence = true;
		MtrbrDebugLog.event("MTRBR-SINGLE-LINE-ZONE", "action=REBUILT zones=" + rebuilt.size()
				+ "sections=" + bidirectional.size() + " topologyRevision=" + topologyRevision + " routeRevision=" + routeRevision);
	}

	/** Publishes a previously compiled definition set; no route scan is performed here. */
	public static void persistCompiledZones(ServerLevel level, Simulator simulator) {
		final State state = STATES.get(simulator);
		if (state == null || !state.zonesPendingPersistence) return;
		SignalBlockSavedData.get(level).setSingleLineZones(state.zones.values());
		// get() republishes the immutable server-thread snapshot after the write.
		SignalBlockSavedData.get(level);
		state.zonesPendingPersistence = false;
		MtrbrDebugLog.event("MTRBR-SINGLE-LINE-ZONE", "action=PERSISTED zones=" + state.zones.size());
	}

	public static List<String> getZoneIds(Simulator simulator, Collection<PathSnapshot.PathTraversal> traversals,
			SignalBlockSavedData.Snapshot savedData) {
		final State state = state(simulator);
		loadPersistedZones(state, savedData);
		if (state.zones.isEmpty() || traversals.isEmpty()) return List.of();
		final Set<String> sections = new HashSet<>();
		for (final PathSnapshot.PathTraversal traversal : traversals) {
			if (traversal.sectionId() != null && !traversal.sectionId().isBlank()) sections.add(traversal.sectionId());
		}
		return state.zones.values().stream()
				.filter(zone -> !Collections.disjoint(zone.sectionIds(), sections))
				.map(SignalBlockSavedData.SingleLineZoneDefinition::zoneId).sorted().toList();
	}

	public static boolean areZonesAvailable(Simulator simulator, List<String> zoneIds, String requestId) {
		final State state = state(simulator);
		for (final String zoneId : zoneIds) {
			final ZoneLease lease = state.zoneLeases.get(zoneId);
			if (lease != null && !requestId.equals(lease.requestId)) {
				MtrbrDebugLog.event("MTRBR-SINGLE-LINE-ZONE", "action=CONFLICT zone=" + zoneId
						+ " request=" + requestId + " ownerRequest=" + lease.requestId + " ownerVehicle=" + lease.vehicleId);
				return false;
			}
		}
		return true;
	}

	static List<String> unownedZones(Simulator simulator, List<String> ids, String owner) {
		final State state = STATES.get(simulator);
		return ids.stream().filter(id -> state == null || state.zoneLeases.get(id) == null
				|| !owner.equals(state.zoneLeases.get(id).requestId)).toList();
	}

	public static boolean reserveZones(Simulator simulator, List<String> zoneIds, String requestId, long vehicleId) {
		if (!areZonesAvailable(simulator, zoneIds, requestId)) return false;
		final State state = state(simulator);
		for (final String zoneId : zoneIds) {
			state.zoneLeases.computeIfAbsent(zoneId, ignored -> new ZoneLease(requestId, vehicleId,
					state.zones.get(zoneId).sectionIds())).reserved = true;
		}
		return true;
	}

	public static boolean lockZones(Simulator simulator, List<String> zoneIds, String requestId) {
		final State state = state(simulator);
		for (final String zoneId : zoneIds) {
			final ZoneLease lease = state.zoneLeases.get(zoneId);
			if (lease == null || !requestId.equals(lease.requestId)) return false;
		}
		for (final String zoneId : zoneIds) state.zoneLeases.get(zoneId).locked = true;
		return true;
	}

	public static void releaseReservedZones(Simulator simulator, List<String> zoneIds, String requestId) {
		final State state = STATES.get(simulator);
		if (state == null) return;
		for (final String zoneId : zoneIds) {
			final ZoneLease lease = state.zoneLeases.get(zoneId);
			if (lease != null && requestId.equals(lease.requestId) && !lease.locked) state.zoneLeases.remove(zoneId);
		}
	}

	/** A locked zone remains held until the train has first entered then physically left every zone section. */
	public static void releaseExitedZones(Simulator simulator, long vehicleId, Set<String> occupiedSections) {
		final State state = STATES.get(simulator);
		if (state == null) return;
		for (final var iterator = state.zoneLeases.entrySet().iterator(); iterator.hasNext();) {
			final Map.Entry<String, ZoneLease> entry = iterator.next();
			final ZoneLease lease = entry.getValue();
			if (lease.vehicleId != vehicleId) continue;
			final boolean intersects = !Collections.disjoint(lease.sectionIds, occupiedSections);
			if (intersects) lease.entered = true;
			else if (lease.entered) {
				MtrbrDebugLog.event("MTRBR-SINGLE-LINE-ZONE", "action=RELEASED zone=" + entry.getKey() + " vehicle=" + vehicleId);
				iterator.remove();
			}
		}
	}

	/** Cancelling a request before it enters a zone must not leave an approach lease behind. */
	public static void releaseUnenteredZones(Simulator simulator, String requestId) {
		final State state = STATES.get(simulator);
		if (state == null) return;
		state.zoneLeases.entrySet().removeIf(entry -> requestId.equals(entry.getValue().requestId) && !entry.getValue().entered);
	}

	/** Preserve entered/locked capacity while the same train receives a new request. */
	static void transferRequestOwner(Simulator simulator, String previous, String next, long vehicleId) {
		final State state = STATES.get(simulator);
		if (state == null) return;
		for (final ZoneLease lease : state.zoneLeases.values()) {
			if (lease.vehicleId == vehicleId && previous.equals(lease.requestId)) lease.requestId = next;
		}
	}

	public static boolean canCreateSidingVehicle(Siding siding) {
		if (siding.getMaxVehicles() != 1) return true;
		final State state = state(SectionStateManager.getCurrentSimulator());
		final Set<Long> fleet = state.sidingFleet.get(fleetId(siding));
		return fleet == null || fleet.isEmpty();
	}

	/** Called only after MTR's original vehicles.add(new Vehicle(...)) has succeeded. */
	public static void registerSidingVehicle(Siding siding, Vehicle vehicle) {
		if (siding.getMaxVehicles() != 1 || vehicle == null) return;
		final State state = state(SectionStateManager.getCurrentSimulator());
		final Set<Long> fleet = state.sidingFleet.computeIfAbsent(fleetId(siding), ignored -> new HashSet<>());
		fleet.add(vehicle.getId());
		MtrbrDebugLog.event("MTRBR-SIDING-FLEET", "action=REGISTER siding=" + siding.getId() + " vehicle=" + vehicle.getId()
				+ " count=" + fleet.size());
	}

	public static void releaseSidingVehicle(long vehicleId, String reason) {
		for (final State state : STATES.values()) {
			for (final var iterator = state.sidingFleet.entrySet().iterator(); iterator.hasNext();) {
				final Map.Entry<String, Set<Long>> entry = iterator.next();
				if (entry.getValue().remove(vehicleId)) {
					MtrbrDebugLog.event("MTRBR-SIDING-FLEET", "action=RELEASE vehicle=" + vehicleId + " resource=" + entry.getKey() + " reason=" + reason);
				}
				if (entry.getValue().isEmpty()) iterator.remove();
			}
		}
	}

	/** Only a confirmed MTR lifecycle removal may discard an occupied capacity lease. */
	public static void releaseVehicle(long vehicleId, String reason) {
		for (final State state : STATES.values()) {
			state.zoneLeases.entrySet().removeIf(entry -> entry.getValue().vehicleId == vehicleId);
		}
		releaseSidingVehicle(vehicleId, reason);
	}

	public static void resetAll() {
		STATES.clear();
	}

	private static State state(Simulator simulator) {
		if (simulator == null) throw new IllegalStateException("Capacity lease access outside Simulator lifecycle");
		return STATES.computeIfAbsent(simulator, ignored -> new State());
	}

	private static void loadPersistedZones(State state, SignalBlockSavedData.Snapshot savedData) {
		if (!state.zones.isEmpty() || savedData.singleLineZones().isEmpty()) return;
		state.zones = Map.copyOf(savedData.singleLineZones());
		MtrbrDebugLog.event("MTRBR-SINGLE-LINE-ZONE", "action=LOADED_PERSISTED zones=" + state.zones.size());
	}

	private static List<DirectedSection> directedSections(List<PathData> pathData) {
		final List<DirectedSection> result = new ArrayList<>();
		for (final PathData data : pathData) {
			if (data == null || data.getRail() == null) continue;
			final Position start = data.reversePositions ? data.getOrderedPosition2() : data.getOrderedPosition1();
			final Position end = data.reversePositions ? data.getOrderedPosition1() : data.getOrderedPosition2();
			if (start == null || end == null) continue;
			result.add(new DirectedSection(data.getRail().getHexId(), positionKey(start), positionKey(end)));
		}
		return result;
	}

	private static String positionKey(Position position) {
		return position.getX() + "," + position.getY() + "," + position.getZ();
	}

	private static String fleetId(Siding siding) {
		return "siding-fleet:" + siding.getId();
	}

	private record DirectedSection(String sectionId, String start, String end) {
	}

	private static final class ZoneLease {
		private String requestId;
		private final long vehicleId;
		private final List<String> sectionIds;
		private boolean reserved;
		private boolean locked;
		private boolean entered;

		private ZoneLease(String requestId, long vehicleId, List<String> sectionIds) {
			this.requestId = requestId;
			this.vehicleId = vehicleId;
			this.sectionIds = List.copyOf(sectionIds);
		}
	}

	private static final class State {
		private Map<String, SignalBlockSavedData.SingleLineZoneDefinition> zones = Map.of();
		private final Map<String, ZoneLease> zoneLeases = new HashMap<>();
		private final Map<String, Set<Long>> sidingFleet = new HashMap<>();
		private long routeRevision;
		private boolean zonesPendingPersistence;
	}

	private static final class DisjointSet {
		private final Map<String, String> parents = new HashMap<>();

		private DisjointSet(Collection<String> values) { values.forEach(value -> parents.put(value, value)); }
		private String find(String value) {
			final String parent = parents.get(value);
			if (parent.equals(value)) return value;
			final String root = find(parent);
			parents.put(value, root);
			return root;
		}
		private void union(String first, String second) {
			final String firstRoot = find(first);
			final String secondRoot = find(second);
			if (!firstRoot.equals(secondRoot)) parents.put(firstRoot, secondRoot);
		}
	}
}
