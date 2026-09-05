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
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable server-side view of one MTR VehicleExtraData.immutablePath. */
public final class PathSnapshot {
	private final List<PathSection> sections;
	private final List<PathTraversal> traversals;
	private final List<ReverseOccurrence> reverseOccurrences;
	private final Set<Integer> diagnosedStops = new HashSet<>();
	private final String fingerprint;
	private final Map<Simulator, TopologyMatch> topologyMatches = Collections.synchronizedMap(new IdentityHashMap<>());
	private final Map<String, FaceTraversalPoints> faceTraversalPoints = new HashMap<>();
	private static final Map<Vehicle, CachedSnapshot> VEHICLE_CACHE = Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private PathSnapshot(List<PathSection> sections, String fingerprint) {
		this.sections = List.copyOf(sections);
		final List<PathTraversal> pathTraversals = new ArrayList<>();
		for (int index = 0; index < this.sections.size(); index++) {
			final PathSection section = this.sections.get(index);
			pathTraversals.add(new PathTraversal(index, section.sectionId(), section.startDistance(), section.endDistance(), section.startNode(), section.endNode(), section.travelAngle(), section.reversePositions(), section.isPlatform(), section.isSiding(), section.stopIndex(), section.canTurnBack()));
		}
		this.traversals = List.copyOf(pathTraversals);
		final List<ReverseOccurrence> reversals = new ArrayList<>();
		for (int index = 1; index < traversals.size(); index++) {
			final PathTraversal before = traversals.get(index - 1);
			final PathTraversal after = traversals.get(index);
			// Mirror MTR PathData.isOppositeRail(): actual endpoint reversal,
			// not rail capability or a large change of heading through a curve.
			if (!before.startNode().equals(before.endNode())
					&& before.startNode().equals(after.endNode()) && before.endNode().equals(after.startNode())) {
				reversals.add(new ReverseOccurrence(index - 1, index));
			}
		}
		this.reverseOccurrences = List.copyOf(reversals);
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
					startNode, endNode, travelAngle, reversePositions, pathData.getDwellTime(), rail != null && rail.isPlatform(), rail != null && rail.isSiding(), pathData.getStopIndex(), rail != null && rail.canTurnBack()));
			signature.append(sectionId).append('@').append(pathData.getStartDistance()).append('-').append(pathData.getEndDistance()).append(':').append(railSignature).append(';');
			signature.append("dir=").append(travelAngle).append(':').append(pathData.reversePositions).append(';');
		}
		final PathSnapshot snapshot = new PathSnapshot(sections, sha256(signature.toString()));
		VEHICLE_CACHE.put(vehicle, new CachedSnapshot(immutablePath, snapshot));
		return snapshot;
	}

	public static void clear(Vehicle vehicle) {
		if (vehicle != null) VEHICLE_CACHE.remove(vehicle);
	}

	public static void clearAll() {
		VEHICLE_CACHE.clear();
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
		final List<FaceCandidate> candidates = new ArrayList<>();
		// Only a node occurrence whose direction matches the face is a traversal.
		// A physical signal may occur more than once in an immutable path (notably
		// on the return leg after turnback), so retain every matching occurrence.
		for (final SignalFace face : topology.faces().values()) {
			getNodeDistances(face.nodePos()).stream()
					.filter(point -> circularDifference(point.travelAngle(), face.travelAngle()) < 90)
					.filter(point -> point.distance() >= 0)
					.forEach(point -> candidates.add(new FaceCandidate(face, point)));
		}
		candidates.sort(java.util.Comparator.comparingDouble(candidate -> candidate.nodeDistance().distance()));
		final Map<String, Integer> occurrences = new HashMap<>();
		final List<FaceTraversal> points = new ArrayList<>();
		for (final FaceCandidate candidate : candidates) {
			final SignalFace face = candidate.face();
			final NodeDistance nodeDistance = candidate.nodeDistance();
			final double distance = nodeDistance.distance();
			final int occurrenceIndex = occurrences.merge(face.id(), 1, Integer::sum) - 1;
			points.add(new FaceTraversal(face.id(), pathIndexAtDistance(distance), occurrenceIndex, face,
					distance, nodeDistance.travelAngle(), directionKey(nodeDistance.travelAngle())));
		}
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
			// A shared endpoint belongs to the traversal leaving the node. Interior
			// positions remain strictly inside one traversal, with no epsilon overlap.
			if (distance == traversal.startDistance()
					|| distance > traversal.startDistance() && distance < traversal.endDistance()) return traversal.index();
		}
		return !traversals.isEmpty() && distance == traversals.get(traversals.size() - 1).endDistance()
				? traversals.get(traversals.size() - 1).index() : -1;
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

	/** Ordered, deduplicated topology nodes for path projection and diagnostics only. */
	public List<String> getPathNodesBetween(double startDistance, double endDistance) {
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
			if (section.endDistance() > currentDistance + 1.0E-6 && section.dwellTime() > 0) {
				return section.endDistance();
			}
		}
		return getTotalDistance();
	}

	/** Next actual operating stop: a platform dwell boundary or the path terminal. */
	public double getNextOperationalStoppingDistance(double currentDistance) {
		for (final PathSection section : sections) {
			if (section.endDistance() > currentDistance + 1.0E-6 && section.isPlatform() && section.dwellTime() > 0) {
				return section.endDistance();
			}
		}
		return getTotalDistance();
	}

	/**
	 * The next directed protection boundary for a signal traversal. A boundary is
	 * either the next same-direction SignalFace or the physical terminal reached
	 * before the immutable path reverses. Terminal nodes are topology boundaries,
	 * not additional Sections.
	 */
	public ProtectionBoundary getNextProtectionBoundary(FaceTraversal face, List<FaceTraversal> faces) {
		final DirectionSegmentEnd segment = directionSegmentEnd(face.pathTraversalIndex());
		final FaceTraversal nextFace = faces.stream()
				.filter(candidate -> candidate.pathTraversalIndex() >= face.pathTraversalIndex())
				.filter(candidate -> candidate.pathTraversalIndex() <= segment.lastTraversalIndex())
				.filter(candidate -> candidate.distance() > face.distance() + 1.0E-6)
				.filter(candidate -> candidate.distance() <= segment.terminal().distance() + 1.0E-6)
				.filter(candidate -> circularDifference(candidate.travelAngle(), face.travelAngle()) < 90)
				.min(java.util.Comparator.comparingDouble(FaceTraversal::distance)).orElse(null);
		return nextFace == null ? ProtectionBoundary.terminal(segment.terminal()) : ProtectionBoundary.face(nextFace);
	}

	/** Resolves a persisted boundary only in the entry occurrence's directed segment. */
	public ProtectionBoundary getProtectionBoundary(FaceTraversal face, List<FaceTraversal> faces, String boundaryId) {
		if (boundaryId == null || boundaryId.isBlank()) return null;
		final DirectionSegmentEnd segment = directionSegmentEnd(face.pathTraversalIndex());
		if (boundaryId.startsWith(TerminalNode.PREFIX)) {
			return segment.terminal().id().equals(boundaryId) ? ProtectionBoundary.terminal(segment.terminal()) : null;
		}
		return faces.stream()
				.filter(candidate -> candidate.pathTraversalIndex() >= face.pathTraversalIndex())
				.filter(candidate -> candidate.pathTraversalIndex() <= segment.lastTraversalIndex())
				.filter(candidate -> candidate.distance() > face.distance() + 1.0E-6)
				.filter(candidate -> candidate.distance() <= segment.terminal().distance() + 1.0E-6)
				.filter(candidate -> candidate.faceId().equals(boundaryId))
				.filter(candidate -> circularDifference(candidate.travelAngle(), face.travelAngle()) < 90)
				.min(java.util.Comparator.comparingDouble(FaceTraversal::distance)).map(ProtectionBoundary::face).orElse(null);
	}

	/** Physical direction boundaries depend on immutablePath, never on a dispatcher or vehicle phase. */
	private DirectionSegmentEnd directionSegmentEnd(int entryTraversalIndex) {
		for (final ReverseOccurrence reversal : reverseOccurrences) {
			if (reversal.beforeTraversalIndex() >= entryTraversalIndex) {
				final PathTraversal before = traversals.get(reversal.beforeTraversalIndex());
				return new DirectionSegmentEnd(before.index(), terminalNode(before.endNode(), before.travelAngle(), before.endDistance(), true));
			}
		}
		if (traversals.isEmpty()) {
			return new DirectionSegmentEnd(-1, new TerminalNode(TerminalNode.PREFIX + "empty", BlockPos.ZERO, 0, 0, false));
		}
		final PathTraversal last = traversals.get(traversals.size() - 1);
		return new DirectionSegmentEnd(last.index(), terminalNode(last.endNode(), last.travelAngle(), last.endDistance(), false));
	}

	public TerminalNode getNextTerminalNode(double currentDistance) {
		if (traversals.isEmpty()) return new TerminalNode(TerminalNode.PREFIX + "empty", BlockPos.ZERO, 0, currentDistance, false);
		for (final PathTraversal traversal : traversals) {
			if (traversal.endDistance() > currentDistance + 1.0E-6) return directionSegmentEnd(traversal.index()).terminal();
		}
		return directionSegmentEnd(traversals.size() - 1).terminal();
	}

	/**
	 * Associate the next platform stop with the same proven reverse occurrence
	 * used for Block boundaries. A native turnback rail may lie beyond the stop;
	 * its endpoint is not replaced by stopDistance. Ordinary dwell is not reversal.
	 */
	public TurnbackWindow getNextTurnbackWindow(double currentDistance) {
		int stopSectionIndex = -1;
		for (int index = 0; index < sections.size(); index++) {
			final PathSection section = sections.get(index);
			// The retained TurnbackIntent handles dwell at an already reached stop.
			if (section.endDistance() > currentDistance + 1.0E-6 && section.isPlatform() && section.dwellTime() > 0) {
				stopSectionIndex = index;
				break;
			}
		}
		if (stopSectionIndex < 0) {
			if (diagnosedStops.add(-1)) MtrbrDebugLog.event("MTRBR-TURNBACK-WINDOW", "pathFingerprint=" + fingerprint
					+ " stopIndex=-1 requiresTurnback=false reason=NO_FUTURE_DWELL_PLATFORM");
			return new TurnbackWindow(getTotalDistance(), getTotalDistance(), -1, false);
		}
		final PathSection stop = sections.get(stopSectionIndex);
		int limit = traversals.size();
		for (int index = stopSectionIndex + 1; index < sections.size(); index++) {
			final PathSection candidate = sections.get(index);
			if (candidate.isPlatform() && candidate.dwellTime() > 0) {
				limit = index;
				break;
			}
		}
		for (final ReverseOccurrence reversal : reverseOccurrences) {
			// Include a reversal directly out of the platform even when the return
			// occurrence is itself marked as the next dwell platform by MTR.
			if (reversal.beforeTraversalIndex() < stopSectionIndex || reversal.beforeTraversalIndex() >= limit) continue;
			final PathTraversal before = traversals.get(reversal.beforeTraversalIndex());
			final PathTraversal after = traversals.get(reversal.afterTraversalIndex());
			if (diagnosedStops.add(stopSectionIndex)) MtrbrDebugLog.event("MTRBR-TURNBACK-OCCURRENCE", "pathFingerprint=" + fingerprint
					+ " stopIndex=" + stop.stopIndex() + " stopDistance=" + stop.endDistance()
					+ " stopTraversalIndex=" + stopSectionIndex
					+ " reverseBeforeTraversal=" + before.index() + " reverseAfterTraversal=" + after.index()
					+ " beforeAngle=" + before.travelAngle() + " afterAngle=" + after.travelAngle()
					+ " endDistance=" + before.endDistance() + " terminal=" + directionSegmentEnd(stopSectionIndex).terminal().id()
					+ " requiresTurnback=true reason=IMMUTABLE_PATH_OPPOSITE_RAIL");
			return new TurnbackWindow(stop.endDistance(), before.endDistance(), stop.stopIndex(), true);
		}
		if (diagnosedStops.add(stopSectionIndex)) MtrbrDebugLog.event("MTRBR-TURNBACK-OCCURRENCE", "pathFingerprint=" + fingerprint
				+ " stopIndex=" + stop.stopIndex() + " stopDistance=" + stop.endDistance() + " stopTraversalIndex=" + stopSectionIndex
				+ " scanLimit=" + limit + " traversalCount=" + traversals.size()
				+ " requiresTurnback=false reason=NO_REVERSE_OCCURRENCE");
		return new TurnbackWindow(stop.endDistance(), stop.endDistance(), stop.stopIndex(), false);
	}

	private record ReverseOccurrence(int beforeTraversalIndex, int afterTraversalIndex) { }
	private record DirectionSegmentEnd(int lastTraversalIndex, TerminalNode terminal) { }

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

	private static TerminalNode terminalNode(BlockPos node, double travelAngle, double distance, boolean turnback) {
		return new TerminalNode(TerminalNode.PREFIX + nodeKey(node) + ":" + directionKey(travelAngle), node, directionKey(travelAngle), distance, turnback);
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

	public record PathSection(String sectionId, double startDistance, double endDistance, String railFingerprint, BlockPos startNode, BlockPos endNode, double travelAngle, boolean reversePositions, long dwellTime, boolean isPlatform, boolean isSiding, int stopIndex, boolean canTurnBack) {
	}

	/** One ordered occurrence of one immutable-path Rail traversal. */
	/** PathData-facing direction from startNode toward endNode; the canonical section traversal heading. */
	public record PathTraversal(int index, String sectionId, double startDistance, double endDistance, BlockPos startNode, BlockPos endNode, double travelAngle, boolean reversePositions, boolean isPlatform, boolean isSiding, int stopIndex, boolean canTurnBack) {
	}

	/** Planned native terminal operation for the next scheduled stop. */
	public record TurnbackWindow(double stopDistance, double endDistance, int stopIndex, boolean requiresTurnback) {
	}

	/** A directed terminal boundary; it carries no Section state. */
	public record TerminalNode(String id, BlockPos node, int direction, double distance, boolean turnback) {
		public static final String PREFIX = "terminal:";
	}

	/** A signal's next protection boundary, represented without inventing a reverse Section. */
	public record ProtectionBoundary(String id, double distance, FaceTraversal face, TerminalNode terminal) {
		public static ProtectionBoundary face(FaceTraversal face) { return new ProtectionBoundary(face.faceId(), face.distance(), face, null); }
		public static ProtectionBoundary terminal(TerminalNode terminal) { return new ProtectionBoundary(terminal.id(), terminal.distance(), null, terminal); }
		public boolean isTerminal() { return terminal != null; }
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
		/** Canonical value used across saved Blocks, Authorization and Activity projections. */
		public String canonical() {
			return faceId + "|path=" + pathTraversalIndex + "|occurrence=" + occurrenceIndex;
		}

		public boolean sameIdentity(FaceTraversalKey other) {
			return other != null && canonical().equals(other.canonical());
		}

		@Override
		public String toString() {
			return faceId + "@" + pathTraversalIndex + ":" + occurrenceIndex;
		}
	}

	public record NodeDistance(BlockPos node, double distance, double travelAngle) {
	}

	private record FaceTraversalPoints(long revision, List<FaceTraversal> points) {
	}

	private record FaceCandidate(SignalFace face, NodeDistance nodeDistance) {
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
