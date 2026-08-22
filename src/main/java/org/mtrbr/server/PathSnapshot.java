package org.mtrbr.server;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable server-side view of one MTR VehicleExtraData.immutablePath. */
public final class PathSnapshot {
	private final List<PathSection> sections;
	private final List<PathTraversal> traversals;
	private final String fingerprint;
	private final Map<Simulator, TopologyMatch> topologyMatches = Collections.synchronizedMap(new IdentityHashMap<>());
	private final Map<String, FaceTraversalPoints> faceTraversalPoints = new HashMap<>();
	private static final Map<Vehicle, CachedSnapshot> VEHICLE_CACHE = Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private PathSnapshot(List<PathSection> sections, String fingerprint) {
		this.sections = List.copyOf(sections);
		final List<PathTraversal> pathTraversals = new ArrayList<>();
		for (int index = 0; index < this.sections.size(); index++) {
			final PathSection section = this.sections.get(index);
			pathTraversals.add(new PathTraversal(index, section.sectionId(), section.startDistance(), section.endDistance(), section.startNode(), section.endNode(), section.travelAngle(), section.reversePositions(), section.isPlatform(), section.isSiding()));
		}
		this.traversals = List.copyOf(pathTraversals);
		this.fingerprint = fingerprint;
	}

	public static PathSnapshot from(Vehicle vehicle) {
		final Object immutablePath = vehicle.vehicleExtraData.immutablePath;
		final CachedSnapshot cached = VEHICLE_CACHE.get(vehicle);
		if (cached != null && cached.immutablePath == immutablePath) {
			return cached.snapshot;
		}
		final List<PathSection> sections = new ArrayList<>();
		final StringBuilder signature = new StringBuilder();
		for (final PathData pathData : vehicle.vehicleExtraData.immutablePath) {
			final Rail rail = pathData.getRail();
			final String sectionId = rail == null ? "" : rail.getHexId();
			final String railSignature = rail == null ? "missing" : railFingerprint(rail);
			final BlockPos orderedPosition1 = toBlockPos(pathData.getOrderedPosition1());
			final BlockPos orderedPosition2 = toBlockPos(pathData.getOrderedPosition2());
			final boolean reversePositions = pathData.reversePositions;
			// PathTraversal follows immutablePath movement, not the rail's stored ordering.
			// reversePositions swaps the physical endpoints and reverses the heading.
			final BlockPos startNode = reversePositions ? orderedPosition2 : orderedPosition1;
			final BlockPos endNode = reversePositions ? orderedPosition1 : orderedPosition2;
			final double travelAngle = normalizeAngle(angle(startNode, endNode));
			sections.add(new PathSection(sectionId, pathData.getStartDistance(), pathData.getEndDistance(), railSignature,
					startNode, endNode, travelAngle, reversePositions, pathData.getDwellTime(), rail != null && rail.isPlatform(), rail != null && rail.isSiding()));
			signature.append(sectionId).append('@').append(pathData.getStartDistance()).append('-').append(pathData.getEndDistance()).append(':').append(railSignature).append(';');
			signature.append("dir=").append(travelAngle).append(':').append(pathData.reversePositions).append(';');
		}
		final PathSnapshot snapshot = new PathSnapshot(sections, sha256(signature.toString()));
		VEHICLE_CACHE.put(vehicle, new CachedSnapshot(immutablePath, snapshot));
		return snapshot;
	}

	public List<PathSection> getSections() {
		return sections;
	}

	/**
	 * Ordered immutable-path traversal instances. A repeated Rail remains a
	 * separate traversal and keeps its immutablePath index.
	 */
	public List<PathTraversal> getTraversals() {
		return traversals;
	}

	public List<PathTraversal> getTraversalsBetween(double startDistance, double endDistance) {
		return traversals.stream()
				.filter(traversal -> traversal.endDistance() > startDistance && traversal.startDistance() < endDistance)
				.toList();
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public boolean isEmpty() {
		return sections.isEmpty();
	}

	public double getTotalDistance() {
		return sections.isEmpty() ? 0 : sections.get(sections.size() - 1).endDistance();
	}

	public double getDistanceAtNode(BlockPos node) {
		for (final PathSection section : sections) {
			if (node.equals(section.startNode())) {
				return section.startDistance();
			}
			if (node.equals(section.endNode())) {
				return section.endDistance();
			}
		}
		return -1;
	}

	/** Direction of travel when this path reaches a node, in Minecraft yaw degrees. */
	public double getTravelAngleAtNode(BlockPos node) {
		return getNodeDistances(node).stream().findFirst().map(NodeDistance::travelAngle).orElse(Double.NaN);
	}

	/** Every traversal of a node in path order. A turnaround may visit the same node more than once. */
	public List<NodeDistance> getNodeDistances(BlockPos node) {
		return getNodeDistances().stream().filter(point -> point.node().equals(node)).toList();
	}

	/** Ordered signal-face traversal instances for one immutable topology revision. */
	public synchronized List<FaceTraversal> getFaceTraversals(String dimension, ServerAspectManager.FaceSnapshot topology) {
		final FaceTraversalPoints cached = faceTraversalPoints.get(dimension);
		if (cached != null && cached.revision == topology.revision()) {
			return cached.points;
		}
		final Map<String, Integer> occurrences = new HashMap<>();
		final List<FaceTraversal> points = new ArrayList<>();
		// Only a node occurrence whose direction matches the face is a traversal.
		// Opposite-direction occurrences are diagnostic-only and never enter routing.
		for (final SignalFace face : topology.faces().values()) {
			final NodeDistance nodeDistance = getNodeDistances(face.nodePos()).stream()
					.filter(point -> circularDifference(point.travelAngle(), face.travelAngle()) < 90)
					.min(java.util.Comparator.comparingDouble(NodeDistance::distance))
					.orElse(null);
			if (nodeDistance == null || nodeDistance.distance() < 0) {
				continue;
			}
			final double distance = nodeDistance.distance();
			final double pathAngle = nodeDistance.travelAngle();
			final int pathIndex = pathIndexAtDistance(distance);
			final int occurrenceIndex = occurrences.merge(face.id(), 1, Integer::sum) - 1;
			final FaceTraversal faceTraversal = new FaceTraversal(face.id(), pathIndex, occurrenceIndex, face, distance, pathAngle, directionKey(pathAngle));
			points.add(faceTraversal);
		}
		points.sort(java.util.Comparator.comparingDouble(FaceTraversal::distance));
		final List<FaceTraversal> immutablePoints = List.copyOf(points);
		faceTraversalPoints.put(dimension, new FaceTraversalPoints(topology.revision(), immutablePoints));
		return immutablePoints;
	}

	/** True only when this traversal is a physical control face for this path direction. */
	public static boolean isDirectionMatched(FaceTraversal traversal) {
		// SignalTopology follows the legacy MTR convention: SignalFace.travelAngle
		// is the train travel direction for that face (signal block facing + 90).
		// Match that direction directly. Matching the opposite heading selects the
		// reverse face and makes a train stop at the signal intended for the other
		// running direction.
		return !Double.isNaN(traversal.travelAngle())
				&& circularDifference(traversal.travelAngle(), traversal.face().travelAngle()) < 90;
	}

	private List<PathNodeTraversal> getPathNodeTraversals() {
		final List<PathNodeTraversal> result = new ArrayList<>();
		if (traversals.isEmpty()) {
			return result;
		}
		// PathData's facingStart is the direction from startNode toward
		// endNode. A signal bound to the node is approached from the previous
		// rail, so the end-node traversal must use the reverse heading. Do not
		// reuse the start heading at both ends of a section.
		final PathTraversal first = traversals.get(0);
		result.add(new PathNodeTraversal(first.startNode(), first.startDistance(), first.index(), first.travelAngle(), true));
		for (final PathTraversal traversal : traversals) {
			result.add(new PathNodeTraversal(traversal.endNode(), traversal.endDistance(), traversal.index(), normalizeAngle(traversal.travelAngle() + 180), false));
		}
		return result;
	}

	private List<NodeDistance> getNodeDistances() {
		final List<NodeDistance> result = new ArrayList<>();
		if (sections.isEmpty()) {
			return result;
		}
		final PathSection first = sections.get(0);
		result.add(new NodeDistance(first.startNode(), first.startDistance(), first.travelAngle()));
		for (final PathSection section : sections) {
			result.add(new NodeDistance(section.endNode(), section.endDistance(), normalizeAngle(section.travelAngle() + 180)));
		}
		return result;
	}

	private double getLegacyDistanceAtNode(BlockPos node) {
		for (PathSection section : sections) {
			if (node.equals(section.startNode())) return section.startDistance();
			if (node.equals(section.endNode())) return section.endDistance();
		}
		return -1;
	}

	private double getLegacyTravelAngleAtNode(BlockPos node) {
		for (PathSection section : sections) {
			if (node.equals(section.startNode())) {
				return section.travelAngle();
			}
			if (node.equals(section.endNode())) {
				return normalizeAngle(section.travelAngle() + 180);
			}
		}
		return Double.NaN;
	}

	private int pathIndexAtDistance(double distance) {
		for (PathTraversal traversal : traversals) {
			if (distance >= traversal.startDistance() - 1e-6 && distance <= traversal.endDistance() + 1e-6) return traversal.index();
		}
		return -1;
	}

	/** Projects ordered traversal instances onto physical Section IDs for SectionState. */
	public List<String> getSectionIds(List<PathTraversal> pathTraversals) {
		return pathTraversals.stream()
				.map(PathTraversal::sectionId)
				.filter(sectionId -> !sectionId.isEmpty())
				.distinct()
				.toList();
	}

	public List<PathSection> getSectionsBetween(double startDistance, double endDistance) {
		return sections.stream().filter(section -> section.endDistance() > startDistance && section.startDistance() < endDistance).toList();
	}


	/** 路径上第一个终点距离大于给定位置的路段终点（用于“授权只到下一段”的出库/出站请求）。 */
	public double getFirstSectionEndAfter(double distance) {
		for (final PathSection section : sections) {
			if (section.endDistance() > distance) {
				return section.endDistance();
			}
		}
		return getTotalDistance();
	}

	/** 有序且去重的路径节点集合（用于“一个节点同时只能被一条进路开放”的联锁检查）。 */
	public List<String> getNodeKeysBetween(double startDistance, double endDistance) {
		final java.util.LinkedHashSet<String> nodes = new java.util.LinkedHashSet<>();
		for (final PathSection section : sections) {
			if (section.endDistance() > startDistance && section.startDistance() < endDistance) {
				nodes.add(nodeKey(section.startNode()));
				nodes.add(nodeKey(section.endNode()));
			}
		}
		return List.copyOf(nodes);
	}

	public double getNextStoppingDistance(double currentDistance) {
		for (final PathSection section : sections) {
			if (section.endDistance() > currentDistance && section.dwellTime() > 0) {
				return section.endDistance();
			}
		}
		return getTotalDistance();
	}

	/** Next actual operating stop: a platform dwell boundary or the path terminal. */
	public double getNextOperationalStoppingDistance(double currentDistance) {
		for (final PathSection section : sections) {
			if (section.endDistance() > currentDistance && section.isPlatform() && section.dwellTime() > 0) {
				return section.endDistance();
			}
		}
		return getTotalDistance();
	}

	/** Returns false when a saved PathData rail is gone or has changed in the current world. */
	public boolean matchesTopology(Simulator simulator) {
		final long revision = SectionStateManager.getTopologyRevision(simulator);
		final TopologyMatch cached = topologyMatches.get(simulator);
		if (cached != null && cached.revision == revision) {
			return cached.matches;
		}
		boolean matches = !sections.isEmpty();
		for (final PathSection pathSection : sections) {
			final Rail rail = simulator.railIdMap.get(pathSection.sectionId());
			if (rail == null || !rail.isValid() || !pathSection.railFingerprint().equals(railFingerprint(rail))) {
				matches = false;
				break;
			}
		}
		topologyMatches.put(simulator, new TopologyMatch(revision, matches));
		return matches;
	}

	private static String railFingerprint(Rail rail) {
		return rail.getHexId() + ":" + rail.getTransportMode() + ":" + rail.getStyles() + ":" + rail.getSpeedLimitKilometersPerHour(true) + ":" + rail.getSpeedLimitKilometersPerHour(false) + ":" + rail.isPlatform() + ":" + rail.isSiding() + ":" + rail.canTurnBack();
	}

	private static BlockPos toBlockPos(org.mtr.core.data.Position position) {
		return new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
	}

	private static BlockPos toBlockPos(org.mtr.core.tool.Vector position) {
		return new BlockPos((int) position.x(), (int) position.y(), (int) position.z());
	}

	private static String nodeKey(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static double angle(BlockPos from, BlockPos to) {
		return Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
	}

	private static double circularDifference(double first, double second) {
		double difference = (first - second) % 360;
		if (difference < -180) {
			difference += 360;
		}
		if (difference > 180) {
			difference -= 360;
		}
		return Math.abs(difference);
	}

	private static String sha256(String value) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			final StringBuilder result = new StringBuilder(digest.length * 2);
			for (final byte item : digest) {
				result.append(String.format("%02x", item));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record PathSection(String sectionId, double startDistance, double endDistance, String railFingerprint, BlockPos startNode, BlockPos endNode, double travelAngle, boolean reversePositions, long dwellTime, boolean isPlatform, boolean isSiding) {
	}

	/** One ordered occurrence of one immutable-path Rail traversal. */
	/** PathData-facing direction from startNode toward endNode; the canonical section traversal heading. */
	public record PathTraversal(int index, String sectionId, double startDistance, double endDistance, BlockPos startNode, BlockPos endNode, double travelAngle, boolean reversePositions, boolean isPlatform, boolean isSiding) {
	}

	private record CachedSnapshot(Object immutablePath, PathSnapshot snapshot) {
	}

	private record TopologyMatch(long revision, boolean matches) {
	}

	/** One occurrence of a SignalFace in one immutable path. */
	/** Direction of this path occurrence at the signal node, projected for node entry; not a section start heading. */
	public record FaceTraversal(String faceId, int pathTraversalIndex, int occurrenceIndex, SignalFace face, double distance, double travelAngle, int direction) {
		public FaceTraversalKey key() {
			return new FaceTraversalKey(faceId, pathTraversalIndex, occurrenceIndex);
		}
	}

	/** Stable identity of one physical SignalFace occurrence on one immutable path. */
	public record FaceTraversalKey(String faceId, int pathTraversalIndex, int occurrenceIndex) {
		@Override
		public String toString() {
			return faceId + "@" + pathTraversalIndex + ":" + occurrenceIndex;
		}
	}

	public record NodeDistance(BlockPos node, double distance, double travelAngle) {
	}

	private record FaceTraversalPoints(long revision, List<FaceTraversal> points) {
	}

	private record PathNodeTraversal(BlockPos node, double distance, int pathTraversalIndex, double travelAngle, boolean pathStart) {
	}

	private static int directionKey(double angle) {
		return (int) Math.round(normalizeAngle(angle) * 1000);
	}

	private static String formatAngle(double angle) {
		return String.format(java.util.Locale.ROOT, "%.3f", angle);
	}

	private static double normalizeAngle(double angle) {
		double normalized = angle % 360;
		return normalized < 0 ? normalized + 360 : normalized;
	}
}
