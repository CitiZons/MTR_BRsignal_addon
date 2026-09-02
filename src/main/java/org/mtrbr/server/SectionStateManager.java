package org.mtrbr.server;

import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.simulation.Simulator;
import org.mtrbr.mixin.VehicleAccess;

import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Server-side, simulation-thread-owned Section facts.
 *
 * This class deliberately does not calculate Signal Aspect or create a
 * RouteRequest yet. Those layers need a server SignalFace topology bridge;
 * this class provides the authoritative physical Section input first.
 */
public final class SectionStateManager {

	/** Ignore zero-width contact at a shared immutable-path node. */
	private static final double OCCUPANCY_BOUNDARY_EPSILON = 1.0E-3;
	private static final ThreadLocal<SimulationState> CURRENT = new ThreadLocal<>();
	private static final Map<Simulator, SimulationState> STATES = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Published only after a complete simulation tick; safe for the Forge server thread to read. */
	private static volatile Map<Simulator, Map<String, SectionSnapshot>> SECTION_SNAPSHOTS = Map.of();

	private SectionStateManager() {
	}

	public static void beginSimulation(Simulator simulator) {
		final SimulationState state = STATES.computeIfAbsent(simulator, ignored -> new SimulationState(simulator));
		state.beginTick();
		CURRENT.set(state);
	}

	public static Simulator getCurrentSimulator() {
		final SimulationState state = CURRENT.get();
		return state == null ? null : state.simulator;
	}

	public static long getCurrentTick() {
		final SimulationState state = CURRENT.get();
		return state == null ? 0 : state.tick;
	}

	public static Simulator getSimulator(String dimension) {
		for (final Simulator simulator : STATES.keySet()) {
			if (simulator.dimension.equals(dimension)) {
				return simulator;
			}
		}
		return null;
	}

	public static void endSimulation(Simulator simulator) {
		final SimulationState state = STATES.get(simulator);
		if (state != null) {
			state.endTick();
		}
		CURRENT.remove();
	}

	/** Clears all simulation-owned state when an integrated/dedicated server stops. */
	public static void resetAll() {
		CURRENT.remove();
		STATES.clear();
		SECTION_SNAPSHOTS = Map.of();
	}

	public static void onTopologySync(Data data) {
		if (data instanceof Simulator simulator) {
			final SimulationState state = STATES.computeIfAbsent(simulator, ignored -> new SimulationState(simulator));
			state.topologyDirty = true;
			CapacityLeaseManager.rebuildSingleLineZones(simulator);
		}
	}

	public static void observeVehicle(Vehicle vehicle) {
		final SimulationState state = CURRENT.get();
		if (state != null) {
			state.observeVehicle(vehicle);
		}
	}

	/** Removes physical occupancy only after MTR has explicitly removed a vehicle. */
	public static void removeVehicleOccupancy(long vehicleId, String reason) {
		for (final SimulationState state : STATES.values()) {
			state.removeVehicleOccupancy(vehicleId, reason);
		}
	}

	/**
	 * Runs at Vehicle.simulate head, before MTR advances railProgress. It creates
	 * or refreshes a RouteRequest so the Movement Gate can protect the first
	 * control boundary on this very simulation step. Physical occupancy remains
	 * a tail observation after MTR movement has completed.
	 */
	public static void prepareVehicle(Vehicle vehicle) {
		final SimulationState state = CURRENT.get();
		if (state != null) {
			state.prepareVehicle(vehicle);
		}
	}

	public static long getTopologyRevision(Simulator simulator) {
		final SimulationState state = STATES.get(simulator);
		return state == null ? 0 : state.topologyRevision;
	}

	/** Monotonic revision for occupancy, reservation, lock and topology changes. */
	public static long getStateRevision(Simulator simulator) {
		final SimulationState state = STATES.get(simulator);
		return state == null ? 0 : state.stateRevision;
	}

	public static Map<String, SectionSnapshot> getSections(Simulator simulator) {
		final SimulationState state = STATES.get(simulator);
		return state == null ? Map.of() : state.snapshot();
	}

	/** Immutable, post-tick Section snapshot for server-thread readers such as Aspect projection. */
	public static Map<String, SectionSnapshot> getPublishedSections(Simulator simulator) {
		return SECTION_SNAPSHOTS.getOrDefault(simulator, Map.of());
	}

	/** Snapshot only the Sections required by one request; never clone the whole network for a check. */
	public static Map<String, SectionSnapshot> getSections(Simulator simulator, Collection<String> sectionIds) {
		final SimulationState state = STATES.get(simulator);
		return state == null ? Map.of() : state.snapshot(sectionIds);
	}

	/** Checks the current physical facts without scanning vehicles. */
	public static boolean areSectionsAvailable(Simulator simulator, Collection<String> sectionIds, String ownerId, long vehicleId, boolean manualDrivingOverride) {
		final SimulationState state = STATES.get(simulator);
		return state != null && state.areSectionsAvailable(sectionIds, ownerId, vehicleId, false);
	}

	/** Adds a request reservation. This method must be called on the simulation thread. */
	public static boolean reserveSections(Simulator simulator, Collection<String> sectionIds, String ownerId, long vehicleId, boolean manualDrivingOverride) {
		final SimulationState state = STATES.get(simulator);
		return state != null && state.reserveSections(sectionIds, ownerId, vehicleId, false);
	}

	/** Promotes a reservation to a route lock. */
	public static boolean lockSections(Simulator simulator, Collection<String> sectionIds, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		return state != null && state.lockSections(sectionIds, ownerId);
	}

	/** Block resources are independent from physical Section IDs. */
	public static boolean areBlocksAvailable(Simulator simulator, Collection<String> blockIds, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		return state != null && state.areBlocksAvailable(blockIds, ownerId);
	}

	public static boolean reserveBlocks(Simulator simulator, Collection<String> blockIds, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		return state != null && state.reserveBlocks(blockIds, ownerId);
	}

	public static boolean lockBlocks(Simulator simulator, Collection<String> blockIds, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		return state != null && state.lockBlocks(blockIds, ownerId);
	}

	/** True only when this owner still holds both the reservation and lock for every Block resource. */
	public static boolean areBlocksReservedAndLockedBy(Simulator simulator, Collection<String> blockIds, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		return state != null && state.areBlocksReservedAndLockedBy(blockIds, ownerId);
	}

	public static void releaseBlocks(Simulator simulator, Collection<String> blockIds, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		if (state != null) {
			state.releaseBlocks(blockIds, ownerId);
		}
	}

	public static boolean isBlockConflicted(Simulator simulator, String blockId, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		return state == null || !state.isBlockAvailable(blockId, ownerId);
	}

	public static void releaseSections(Simulator simulator, Collection<String> sectionIds, String ownerId) {
		final SimulationState state = STATES.get(simulator);
		if (state != null) {
			state.releaseSections(sectionIds, ownerId);
		}
	}

	/**
	 * Drops reservations/locks whose request no longer has a live authorization.
	 * Physical occupancy is deliberately left untouched; it is maintained from
	 * vehicle head/tail observations in applyVehicleOccupancy().
	 */
	public static void releaseStaleReservations(Simulator simulator, Collection<String> activeRequestIds) {
		releaseStaleReservations(simulator, activeRequestIds, Map.of(), Map.of());
	}

	/** Resource-level stale cleanup for Section and Block leases. */
	public static void releaseStaleReservations(Simulator simulator, Collection<String> activeRequestIds,
			Map<String, Set<String>> retainedSectionOwners, Map<String, Set<String>> retainedBlockOwners) {
		final SimulationState state = STATES.get(simulator);
		if (state != null) {
			state.releaseStaleReservations(activeRequestIds == null ? Set.of() : Set.copyOf(activeRequestIds), retainedSectionOwners, retainedBlockOwners);
		}
	}

	public static final class SectionSnapshot {
		public final String sectionId;
		public final boolean exists;
		public final long topologyRevision;
		public final Set<Long> occupiedBy;
		public final Set<Long> manualOverrideBy;
		public final Set<String> reservedBy;
		public final Set<String> lockedBy;

		private SectionSnapshot(String sectionId, boolean exists, long topologyRevision, Set<Long> occupiedBy, Set<Long> manualOverrideBy, Set<String> reservedBy, Set<String> lockedBy) {
			this.sectionId = sectionId;
			this.exists = exists;
			this.topologyRevision = topologyRevision;
			this.occupiedBy = Set.copyOf(occupiedBy);
			this.manualOverrideBy = Set.copyOf(manualOverrideBy);
			this.reservedBy = Set.copyOf(reservedBy);
			this.lockedBy = Set.copyOf(lockedBy);
		}
	}

	private static final class SectionRecord {
		private final String sectionId;
		private boolean exists;
		private long topologyRevision;
		private String topologyFingerprint = "";
		private final Set<Long> occupiedBy = new HashSet<>();
		private final Set<Long> manualOverrideBy = new HashSet<>();
		private final Set<String> reservedBy = new HashSet<>();
		private final Set<String> lockedBy = new HashSet<>();

		private SectionRecord(String sectionId) {
			this.sectionId = sectionId;
		}
	}

	private static final class SimulationState {
		private final Simulator simulator;
		private final Map<String, SectionRecord> sections = new HashMap<>();
		private final Map<String, Set<String>> blockReservedBy = new HashMap<>();
		private final Map<String, Set<String>> blockLockedBy = new HashMap<>();
		private final Map<Long, Set<String>> vehicleSections = new HashMap<>();
		private final Set<Long> observedVehicles = new HashSet<>();
		private long topologyRevision;
		private long stateRevision;
		private long publishedStateRevision = -1;
		private long tick;
		private long lastPublishTick = Long.MIN_VALUE;
		private long nextFallbackTopologyCheck;
		private boolean topologyDirty = true;

		private SimulationState(Simulator simulator) {
			this.simulator = simulator;
			refreshTopology();
		}

		private void beginTick() {
			tick++;
			observedVehicles.clear();
			if (topologyDirty || tick >= nextFallbackTopologyCheck) {
				refreshTopology();
				topologyDirty = false;
				nextFallbackTopologyCheck = tick + 200;
			}
		}

		private void endTick() {
			// Publish immediately after a state change, with a one-second fallback
			// for stable state. Simulation-thread facts remain authoritative here;
			// this only throttles immutable readers' snapshots.
			if (stateRevision != publishedStateRevision || tick - lastPublishTick >= 20) {
				publishSnapshot();
			}
		}

		private void publishSnapshot() {
			final Map<String, SectionSnapshot> snapshot = snapshot();
			final Map<Simulator, Map<String, SectionSnapshot>> next = new IdentityHashMap<>(SECTION_SNAPSHOTS);
			next.put(simulator, snapshot);
			SECTION_SNAPSHOTS = Collections.unmodifiableMap(next);
			publishedStateRevision = stateRevision;
			lastPublishTick = tick;
		}

		private void refreshTopology() {
			final Set<String> currentIds = new HashSet<>();
			boolean changed = false;
			for (final Rail rail : simulator.rails) {
				final String sectionId = rail.getHexId();
				currentIds.add(sectionId);
				final SectionRecord section = sections.computeIfAbsent(sectionId, SectionRecord::new);
				final boolean exists = rail.isValid();
				final String fingerprint = fingerprint(rail);
				if (section.exists != exists || !section.topologyFingerprint.equals(fingerprint)) {
					changed = true;
				}
				section.exists = exists;
				section.topologyFingerprint = fingerprint;
			}
			for (final SectionRecord section : sections.values()) {
				if (!currentIds.contains(section.sectionId)) {
					if (section.exists) {
						changed = true;
					}
					section.exists = false;
				}
			}
			if (changed) {
				topologyRevision++;
				stateRevision++;
			}
			sections.values().forEach(section -> section.topologyRevision = topologyRevision);
		}

		private void observeVehicle(Vehicle vehicle) {
			final long vehicleId = vehicle.getId();
			observedVehicles.add(vehicleId);
			final VehicleExtraData extraData = vehicle.vehicleExtraData;
			final double head = ((VehicleAccess) vehicle).mtrbr$getRailProgress();
			if (head < 0 || extraData.immutablePath.isEmpty()) {
				// A transiently unavailable immutable path is not a confirmed removal.
				// Keep the previous occupancy until MTR removal is delivered explicitly.
				return;
			}
			final double tail = head - extraData.getTotalVehicleLength();
			final Set<String> occupied = new HashSet<>();
			final boolean manualOverride = RouteRequestManager.isManualDrivingOverride(simulator, vehicleId);
			for (final PathData pathData : extraData.immutablePath) {
				// Shrink both vehicle endpoints before testing overlap. This makes a
				// shared node belong to neither adjacent Rail until the vehicle has
				// physically entered it, independently of traversal direction.
				if (pathData.getEndDistance() <= tail + OCCUPANCY_BOUNDARY_EPSILON
						|| pathData.getStartDistance() >= head - OCCUPANCY_BOUNDARY_EPSILON) {
					continue;
				}
				final String sectionId = sectionId(pathData);
				if (sectionId == null) {
					continue;
				}
				occupied.add(sectionId);
			}
			applyVehicleOccupancy(vehicleId, occupied, manualOverride);
			RouteRequestManager.observeVehicle(vehicle, head, tail, occupied);
		}

		private void prepareVehicle(Vehicle vehicle) {
			final VehicleExtraData extraData = vehicle.vehicleExtraData;
			final double head = ((VehicleAccess) vehicle).mtrbr$getRailProgress();
			if (head < 0 || extraData.immutablePath.isEmpty()) {
				return;
			}
			final double tail = head - extraData.getTotalVehicleLength();
			RouteRequestManager.observeVehicle(vehicle, head, tail, vehicleSections.getOrDefault(vehicle.getId(), Set.of()));
		}

		private void applyVehicleOccupancy(long vehicleId, Set<String> nextSections, boolean manualOverride) {
			final Set<String> previousSections = vehicleSections.getOrDefault(vehicleId, Set.of());
			boolean changed = false;
			for (final String sectionId : previousSections) {
				if (!nextSections.contains(sectionId)) {
					final SectionRecord section = sections.get(sectionId);
					if (section != null) {
						changed |= section.occupiedBy.remove(vehicleId);
						changed |= section.manualOverrideBy.remove(vehicleId);
					}
				}
			}
			for (final String sectionId : nextSections) {
				final SectionRecord section = sections.computeIfAbsent(sectionId, SectionRecord::new);
				changed |= section.occupiedBy.add(vehicleId);
				if (manualOverride) {
					changed |= section.manualOverrideBy.add(vehicleId);
				} else {
					changed |= section.manualOverrideBy.remove(vehicleId);
				}
			}
			if (nextSections.isEmpty()) {
				vehicleSections.remove(vehicleId);
			} else {
				vehicleSections.put(vehicleId, Set.copyOf(nextSections));
			}
			if (changed) {
				stateRevision++;
			}
		}

		private void removeVehicleOccupancy(long vehicleId, String reason) {
			if (!vehicleSections.containsKey(vehicleId)) return;
			applyVehicleOccupancy(vehicleId, Set.of(), false);
			observedVehicles.remove(vehicleId);
			MtrbrDebugLog.event("MTRBR-OCCUPANCY-RELEASE", "vehicle=" + vehicleId + " reason=" + reason + " source=MTR_LIFECYCLE");
		}

		private boolean areSectionsAvailable(Collection<String> sectionIds, String ownerId, long vehicleId, boolean manualDrivingOverride) {
			for (final String sectionId : sectionIds) {
				final SectionRecord section = sections.get(sectionId);
				if (section == null || !section.exists) {
					return false;
				}
				if (section.occupiedBy.stream().anyMatch(occupant -> occupant != vehicleId)) {
					return false;
				}
				if (section.lockedBy.stream().anyMatch(owner -> !owner.equals(ownerId)) || section.reservedBy.stream().anyMatch(owner -> !owner.equals(ownerId))) {
					return false;
				}
			}
			return true;
		}

		private boolean areBlocksAvailable(Collection<String> blockIds, String ownerId) {
			for (final String blockId : blockIds) {
				if (blockId == null || blockId.isBlank()) {
					return false;
				}
				if (blockReservedBy.getOrDefault(blockId, Set.of()).stream().anyMatch(owner -> !owner.equals(ownerId))
						|| blockLockedBy.getOrDefault(blockId, Set.of()).stream().anyMatch(owner -> !owner.equals(ownerId))) {
					return false;
				}
			}
			return true;
		}

		private boolean isBlockAvailable(String blockId, String ownerId) {
			return areBlocksAvailable(java.util.List.of(blockId), ownerId);
		}

		private boolean reserveBlocks(Collection<String> blockIds, String ownerId) {
			if (!areBlocksAvailable(blockIds, ownerId)) {
				return false;
			}
			for (final String blockId : blockIds) {
				blockReservedBy.computeIfAbsent(blockId, ignored -> new HashSet<>()).add(ownerId);
			}
			stateRevision++;
			return true;
		}

		private boolean lockBlocks(Collection<String> blockIds, String ownerId) {
			if (!areBlocksAvailable(blockIds, ownerId)) {
				return false;
			}
			for (final String blockId : blockIds) {
				if (!blockReservedBy.getOrDefault(blockId, Set.of()).contains(ownerId)) {
					return false;
				}
			}
			for (final String blockId : blockIds) {
				blockLockedBy.computeIfAbsent(blockId, ignored -> new HashSet<>()).add(ownerId);
			}
			stateRevision++;
			return true;
		}

		private boolean areBlocksReservedAndLockedBy(Collection<String> blockIds, String ownerId) {
			if (blockIds.isEmpty() || ownerId == null || ownerId.isBlank()) {
				return false;
			}
			for (final String blockId : blockIds) {
				if (!blockReservedBy.getOrDefault(blockId, Set.of()).contains(ownerId)
						|| !blockLockedBy.getOrDefault(blockId, Set.of()).contains(ownerId)) {
					return false;
				}
			}
			return true;
		}

		private void releaseBlocks(Collection<String> blockIds, String ownerId) {
			boolean changed = false;
			for (final String blockId : blockIds) {
				final Set<String> reserved = blockReservedBy.get(blockId);
				final Set<String> locked = blockLockedBy.get(blockId);
				if (reserved != null) {
					changed |= reserved.remove(ownerId);
					if (reserved.isEmpty()) blockReservedBy.remove(blockId);
				}
				if (locked != null) {
					changed |= locked.remove(ownerId);
					if (locked.isEmpty()) blockLockedBy.remove(blockId);
				}
			}
			if (changed) stateRevision++;
		}

		private boolean reserveSections(Collection<String> sectionIds, String ownerId, long vehicleId, boolean manualDrivingOverride) {
			if (!areSectionsAvailable(sectionIds, ownerId, vehicleId, manualDrivingOverride)) {
				return false;
			}
			sectionIds.forEach(sectionId -> sections.get(sectionId).reservedBy.add(ownerId));
			stateRevision++;
			return true;
		}

		private boolean lockSections(Collection<String> sectionIds, String ownerId) {
			for (final String sectionId : sectionIds) {
				final SectionRecord section = sections.get(sectionId);
				if (section == null || !section.exists || !section.reservedBy.contains(ownerId) || section.lockedBy.stream().anyMatch(owner -> !owner.equals(ownerId))) {
					return false;
				}
			}
			sectionIds.forEach(sectionId -> sections.get(sectionId).lockedBy.add(ownerId));
			stateRevision++;
			return true;
		}

		private void releaseSections(Collection<String> sectionIds, String ownerId) {
			boolean changed = false;
			for (final String sectionId : sectionIds) {
				final SectionRecord section = sections.get(sectionId);
				if (section != null) {
					final boolean reservationRemoved = section.reservedBy.remove(ownerId);
					final boolean lockRemoved = section.lockedBy.remove(ownerId);
					changed |= reservationRemoved || lockRemoved;
				}
			}
			if (changed) {
				stateRevision++;
			}
		}

		private void releaseStaleReservations(Set<String> activeRequestIds, Map<String, Set<String>> retainedSectionOwners,
				Map<String, Set<String>> retainedBlockOwners) {
			boolean changed = false;
			for (final SectionRecord section : sections.values()) {
				for (final String owner : Set.copyOf(section.reservedBy)) {
					if (!retainedSectionOwners.getOrDefault(section.sectionId, Set.of()).contains(owner) && !activeRequestIds.contains(owner)) {
						section.reservedBy.remove(owner);
						section.lockedBy.remove(owner);
						changed = true;
						System.out.println("[MTRBR-SECTION-STALE] section=" + section.sectionId + " ownerRequest=" + owner + " reason=OWNER_AUTHORIZATION_MISSING");
					}
				}
				for (final String owner : Set.copyOf(section.lockedBy)) {
					if (!retainedSectionOwners.getOrDefault(section.sectionId, Set.of()).contains(owner) && !activeRequestIds.contains(owner)) {
						section.lockedBy.remove(owner);
						changed = true;
						System.out.println("[MTRBR-SECTION-STALE] section=" + section.sectionId + " ownerRequest=" + owner + " reason=OWNER_AUTHORIZATION_MISSING");
					}
				}
			}
			changed |= releaseStaleBlockOwners(blockReservedBy, activeRequestIds, retainedBlockOwners, "reserved");
			changed |= releaseStaleBlockOwners(blockLockedBy, activeRequestIds, retainedBlockOwners, "locked");
			if (changed) {
				stateRevision++;
			}
		}

		private boolean releaseStaleBlockOwners(Map<String, Set<String>> ownersByBlock, Set<String> activeRequestIds,
				Map<String, Set<String>> retainedBlockOwners, String stateName) {
			boolean changed = false;
			for (final Map.Entry<String, Set<String>> entry : Set.copyOf(ownersByBlock.entrySet())) {
				final Set<String> owners = entry.getValue();
				for (final String owner : Set.copyOf(owners)) {
					if (!retainedBlockOwners.getOrDefault(entry.getKey(), Set.of()).contains(owner) && !activeRequestIds.contains(owner)) {
						owners.remove(owner);
						changed = true;
						System.out.println("[MTRBR-BLOCK-STALE] block=" + entry.getKey() + " ownerRequest=" + owner + " state=" + stateName + " reason=OWNER_AUTHORIZATION_MISSING");
					}
				}
				if (owners.isEmpty()) {
					ownersByBlock.remove(entry.getKey());
				}
			}
			return changed;
		}

		private Map<String, SectionSnapshot> snapshot() {
			final Map<String, SectionSnapshot> result = new HashMap<>();
			sections.forEach((id, section) -> result.put(id, new SectionSnapshot(id, section.exists, section.topologyRevision, section.occupiedBy, section.manualOverrideBy, section.reservedBy, section.lockedBy)));
			return Map.copyOf(result);
		}

		private Map<String, SectionSnapshot> snapshot(Collection<String> sectionIds) {
			final Map<String, SectionSnapshot> result = new HashMap<>();
			for (final String sectionId : sectionIds) {
				final SectionRecord section = sections.get(sectionId);
				if (section != null) {
					result.put(sectionId, new SectionSnapshot(sectionId, section.exists, section.topologyRevision, section.occupiedBy, section.manualOverrideBy, section.reservedBy, section.lockedBy));
				}
			}
			return Map.copyOf(result);
		}
	}

	private static String sectionId(PathData pathData) {
		final Rail rail = pathData.getRail();
		if (rail != null) {
			return rail.getHexId();
		}
		final Position position1 = pathData.getOrderedPosition1();
		final Position position2 = pathData.getOrderedPosition2();
		return position1 == null || position2 == null ? null : TwoPositionsBase.getHexId(position1, position2);
	}

	private static String fingerprint(Rail rail) {
		return rail.getHexId() + ":" + rail.getTransportMode() + ":" + rail.getStyles() + ":" + rail.getSpeedLimitKilometersPerHour(true) + ":" + rail.getSpeedLimitKilometersPerHour(false);
	}
}
