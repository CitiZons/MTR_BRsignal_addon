package org.mtrbr.server;

import org.mtr.core.data.Position;
import org.mtr.core.simulation.Simulator;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interlocking resources for MTR junction nodes. These are not Sections:
 * Sections remain the physical Rail state, while a junction resource protects
 * every branch incident to a node with three or more physical Rails.
 */
public final class JunctionStateManager {
	private static final Map<Simulator, Map<String, JunctionRecord>> STATES = Collections.synchronizedMap(new IdentityHashMap<>());
	private static final Map<Simulator, Map<String, Long>> REQUEST_VEHICLES = Collections.synchronizedMap(new IdentityHashMap<>());

	private JunctionStateManager() {
	}

	public static void registerOwner(Simulator simulator, String requestId, long vehicleId) {
		REQUEST_VEHICLES.computeIfAbsent(simulator, ignored -> new HashMap<>()).put(requestId, vehicleId);
	}

	public static List<String> resourcesFor(Simulator simulator, List<PathSnapshot.PathTraversal> traversals) {
		final Set<String> resources = new java.util.LinkedHashSet<>();
		for (int index = 0; index < traversals.size(); index++) {
			final PathSnapshot.PathTraversal traversal = traversals.get(index);
			final String incoming = index == 0 ? "<entry>" : traversals.get(index - 1).sectionId();
			final String outgoing = index + 1 >= traversals.size() ? "<exit>" : traversals.get(index + 1).sectionId();
			addIfJunction(simulator, resources, traversal.endNode(), incoming, traversal.sectionId(), outgoing);
		}
		return List.copyOf(resources);
	}

	public static boolean conflicts(Simulator simulator, List<String> resources, String owner) {
		return !conflictOwners(simulator, resources, owner).isEmpty();
	}

	public static List<String> conflictOwners(Simulator simulator, List<String> resources, String owner) {
		final Map<String, JunctionRecord> state = STATES.get(simulator);
		if (state == null) return List.of();
		synchronized (state) {
			return resources.stream().map(state::get).filter(java.util.Objects::nonNull)
					.flatMap(record -> java.util.stream.Stream.of(record.reservedBy, record.lockedBy))
					.filter(java.util.Objects::nonNull).filter(item -> !item.equals(owner)).distinct().toList();
		}
	}

	/** Removes locks whose request is no longer represented by an active Authorization. */
	public static void releaseStale(Simulator simulator, Set<String> activeRequestIds) {
		final Map<String, JunctionRecord> state = STATES.get(simulator);
		if (state == null) return;
		final long tick = SectionStateManager.getCurrentTick();
		synchronized (state) {
			for (final JunctionRecord record : state.values().toArray(JunctionRecord[]::new)) {
				final String owner = record.lockedBy != null ? record.lockedBy : record.reservedBy;
				if (owner != null && !activeRequestIds.contains(owner)) {
					MtrbrDebugLog.event("MTRBR-JUNCTION-STALE", "resource=" + record.key + " oldOwner=" + owner
							+ " reason=OWNER_AUTHORIZATION_MISSING createdTick=" + record.createdTick
							+ " lastValidatedTick=" + record.lastValidatedTick);
					record.reservedBy = null;
					record.lockedBy = null;
				}
				if (owner != null) record.lastValidatedTick = tick;
				if (record.reservedBy == null && record.lockedBy == null) state.remove(record.key);
			}
		}
	}

	public static boolean reserve(Simulator simulator, List<String> resources, String owner) {
		final Map<String, JunctionRecord> state = STATES.computeIfAbsent(simulator, ignored -> new HashMap<>());
		synchronized (state) {
			for (final String resource : resources) {
				final JunctionRecord record = state.computeIfAbsent(resource, JunctionRecord::new);
				if (record.reservedBy != null && !record.reservedBy.equals(owner)) {
					return false;
				}
				if (record.lockedBy != null && !record.lockedBy.equals(owner)) {
					return false;
				}
			}
			for (final String resource : resources) {
				final JunctionRecord record = state.get(resource);
				record.reservedBy = owner;
				record.ownerVehicle = ownerVehicle(simulator, owner);
				record.lastValidatedTick = SectionStateManager.getCurrentTick();
				MtrbrDebugLog.event("MTRBR-JUNCTION-OWNER", "resource=" + resource + " ownerVehicle=" + ownerVehicle(simulator, owner) + " ownerRequest=" + owner
						+ " phase=RESERVE createdTick=" + record.createdTick + " lastValidatedTick=" + record.lastValidatedTick);
			}
		}
		return true;
	}

	public static boolean lock(Simulator simulator, List<String> resources, String owner) {
		final Map<String, JunctionRecord> state = STATES.computeIfAbsent(simulator, ignored -> new HashMap<>());
		synchronized (state) {
			for (final String resource : resources) {
				final JunctionRecord record = state.get(resource);
				if (record == null || !owner.equals(record.reservedBy) || (record.lockedBy != null && !owner.equals(record.lockedBy))) {
					return false;
				}
			}
			for (final String resource : resources) {
				final JunctionRecord record = state.get(resource);
				record.lockedBy = owner;
				record.ownerVehicle = ownerVehicle(simulator, owner);
				record.lastValidatedTick = SectionStateManager.getCurrentTick();
				MtrbrDebugLog.event("MTRBR-JUNCTION-OWNER", "resource=" + resource + " ownerVehicle=" + ownerVehicle(simulator, owner) + " ownerRequest=" + owner
						+ " phase=LOCK createdTick=" + record.createdTick + " lastValidatedTick=" + record.lastValidatedTick);
			}
		}
		return true;
	}

	public static void release(Simulator simulator, List<String> resources, String owner) {
		final Map<String, JunctionRecord> state = STATES.get(simulator);
		if (state == null) {
			return;
		}
		synchronized (state) {
			for (final String resource : resources) {
				final JunctionRecord record = state.get(resource);
				if (record != null) {
					if (owner.equals(record.reservedBy)) {
						record.reservedBy = null;
					}
					if (owner.equals(record.lockedBy)) {
						record.lockedBy = null;
					}
				}
			}
		}
	}

	public static void resetAll() {
		STATES.clear();
		REQUEST_VEHICLES.clear();
	}

	private static long ownerVehicle(Simulator simulator, String requestId) {
		return REQUEST_VEHICLES.getOrDefault(simulator, Map.of()).getOrDefault(requestId, Long.MIN_VALUE);
	}

	private static void addIfJunction(Simulator simulator, Set<String> result, net.minecraft.core.BlockPos node, String incoming, String traversed, String outgoing) {
		if (node == null) {
			return;
		}
		final Position position = new Position(node.getX(), node.getY(), node.getZ());
		final Map<?, ?> outgoingRails = simulator.positionsToRail.get(position);
		if (outgoingRails != null && outgoingRails.size() >= 3) {
			result.add(key(node, incoming, traversed, outgoing));
		}
	}

	private static String key(net.minecraft.core.BlockPos node, String incoming, String traversed, String outgoing) {
		return node.getX() + "," + node.getY() + "," + node.getZ() + "|in=" + incoming + "|through=" + traversed + "|out=" + outgoing;
	}

	private static final class JunctionRecord {
		private final String key;
		private String reservedBy;
		private String lockedBy;
		private final long createdTick;
		private long lastValidatedTick;
		private long ownerVehicle = Long.MIN_VALUE;

		private JunctionRecord(String key) {
			this.key = key;
			this.createdTick = SectionStateManager.getCurrentTick();
			this.lastValidatedTick = this.createdTick;
		}
	}
}
