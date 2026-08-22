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

	private JunctionStateManager() {
	}

	public static List<String> resourcesFor(Simulator simulator, List<PathSnapshot.PathTraversal> traversals) {
		final Set<String> resources = new java.util.LinkedHashSet<>();
		for (final PathSnapshot.PathTraversal traversal : traversals) {
			// A junction resource represents an incoming approach, not the whole
			// node. Two distinct rails entering the same node in the same travel
			// direction therefore conflict, while unrelated approaches can coexist.
			addIfJunction(simulator, resources, traversal.endNode(), traversal.travelAngle());
		}
		return List.copyOf(resources);
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
				state.get(resource).reservedBy = owner;
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
				state.get(resource).lockedBy = owner;
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
	}

	private static void addIfJunction(Simulator simulator, Set<String> result, net.minecraft.core.BlockPos node, double travelAngle) {
		if (node == null) {
			return;
		}
		final Position position = new Position(node.getX(), node.getY(), node.getZ());
		final Map<?, ?> outgoing = simulator.positionsToRail.get(position);
		if (outgoing != null && outgoing.size() >= 3) {
			result.add(key(node, travelAngle));
		}
	}

	private static String key(net.minecraft.core.BlockPos node, double travelAngle) {
		final double normalized = ((travelAngle % 360) + 360) % 360;
		final int direction = (int) Math.round(normalized * 1000) % 360000;
		return node.getX() + "," + node.getY() + "," + node.getZ() + "@" + direction;
	}

	private static final class JunctionRecord {
		private final String key;
		private String reservedBy;
		private String lockedBy;

		private JunctionRecord(String key) {
			this.key = key;
		}
	}
}
