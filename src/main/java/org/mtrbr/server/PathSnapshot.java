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
	private final String fingerprint;
	private final Map<Simulator, TopologyMatch> topologyMatches = Collections.synchronizedMap(new IdentityHashMap<>());
	private final Map<String, SignalPoints> signalPoints = new HashMap<>();
	private static final Map<Vehicle, CachedSnapshot> VEHICLE_CACHE = Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private PathSnapshot(List<PathSection> sections, String fingerprint) {
		this.sections = List.copyOf(sections);
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
			sections.add(new PathSection(sectionId, pathData.getStartDistance(), pathData.getEndDistance(), railSignature,
					toBlockPos(pathData.getOrderedPosition1()), toBlockPos(pathData.getOrderedPosition2()), pathData.getDwellTime(), rail != null && rail.isPlatform(), rail != null && rail.isSiding()));
			signature.append(sectionId).append('@').append(pathData.getStartDistance()).append('-').append(pathData.getEndDistance()).append(':').append(railSignature).append(';');
		}
		final PathSnapshot snapshot = new PathSnapshot(sections, sha256(signature.toString()));
		VEHICLE_CACHE.put(vehicle, new CachedSnapshot(immutablePath, snapshot));
		return snapshot;
	}

	public List<PathSection> getSections() {
		return sections;
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
		for (int index = 0; index < sections.size(); index++) {
			final PathSection section = sections.get(index);
			if (node.equals(section.startNode())) {
				return angle(section.startNode(), section.endNode());
			}
			if (node.equals(section.endNode())) {
				if (index + 1 < sections.size() && node.equals(sections.get(index + 1).startNode())) {
					return angle(sections.get(index + 1).startNode(), sections.get(index + 1).endNode());
				}
				return angle(section.startNode(), section.endNode());
			}
		}
		return Double.NaN;
	}

	/** Cached path-local control points for one immutable SignalFace topology revision. */
	public synchronized List<FaceDistance> getFaceDistances(String dimension, ServerAspectManager.FaceSnapshot topology) {
		final SignalPoints cached = signalPoints.get(dimension);
		if (cached != null && cached.revision == topology.revision()) {
			return cached.points;
		}
		final List<FaceDistance> points = topology.faces().values().stream()
				.map(face -> new FaceDistance(face, getDistanceAtNode(face.nodePos())))
				.filter(point -> point.distance() >= 0 && travelsInFaceDirection(point.face()))
				.sorted(java.util.Comparator.comparingDouble(FaceDistance::distance))
				.toList();
		signalPoints.put(dimension, new SignalPoints(topology.revision(), points));
		return points;
	}

	public List<String> getSectionIdsBetween(double startDistance, double endDistance) {
		return sections.stream()
				.filter(section -> section.endDistance() > startDistance && section.startDistance() < endDistance)
				.map(PathSection::sectionId)
				.filter(sectionId -> !sectionId.isEmpty())
				.distinct()
				.toList();
	}

	public List<PathSection> getSectionsBetween(double startDistance, double endDistance) {
		return sections.stream().filter(section -> section.endDistance() > startDistance && section.startDistance() < endDistance).toList();
	}

	/** 把路径按 SignalFace 边界切成闭塞块，每个块内包含若干 Rail Section。 */
	public List<SignalBlock> getSignalBlocksBetween(List<FaceDistance> faces, double startDistance, double endDistance) {
		final List<SignalBlock> blocks = new ArrayList<>();
		for (final PathSection section : getSectionsBetween(startDistance, endDistance)) {
			final double center = (section.startDistance() + section.endDistance()) / 2;
			FaceDistance previous = null;
			FaceDistance next = null;
			for (final FaceDistance faceDistance : faces) {
				if (faceDistance.distance() <= center) {
					previous = faceDistance;
				} else {
					next = faceDistance;
					break;
				}
			}
			final String startId = previous == null ? "path-start" : previous.face().id();
			final String endId = next == null ? "path-end" : next.face().id();
			final String blockId = startId + "->" + endId;
			if (blocks.isEmpty() || !blocks.get(blocks.size() - 1).blockId().equals(blockId)) {
				blocks.add(new SignalBlock(blockId, section.startDistance(), section.endDistance(), new ArrayList<>()));
			}
			final SignalBlock last = blocks.get(blocks.size() - 1);
			last.railIds().add(section.sectionId());
			if (section.endDistance() > last.endDistance()) {
				blocks.set(blocks.size() - 1, new SignalBlock(last.blockId(), last.startDistance(), section.endDistance(), last.railIds()));
			}
		}
		return List.copyOf(blocks);
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

	private static String nodeKey(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static double angle(BlockPos from, BlockPos to) {
		return Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
	}

	private boolean travelsInFaceDirection(SignalFace face) {
		final double pathAngle = getTravelAngleAtNode(face.nodePos());
		return !Double.isNaN(pathAngle) && circularDifference(pathAngle, face.travelAngle()) < 90;
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

	public record PathSection(String sectionId, double startDistance, double endDistance, String railFingerprint, BlockPos startNode, BlockPos endNode, long dwellTime, boolean isPlatform, boolean isSiding) {
	}

	private record CachedSnapshot(Object immutablePath, PathSnapshot snapshot) {
	}

	private record TopologyMatch(long revision, boolean matches) {
	}

	public record FaceDistance(SignalFace face, double distance) {
	}

	public record SignalBlock(String blockId, double startDistance, double endDistance, List<String> railIds) {
	}

	private record SignalPoints(long revision, List<FaceDistance> points) {
	}
}
