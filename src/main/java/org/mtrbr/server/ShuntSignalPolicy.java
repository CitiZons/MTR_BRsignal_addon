package org.mtrbr.server;

import net.minecraft.core.BlockPos;
import org.mtrbr.data.RouteBinding;
import org.mtrbr.data.RouteContent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable configuration shared with the simulation thread; no client authority. */
public final class ShuntSignalPolicy {
    private static final Map<String, Configuration> CONFIGURATIONS = new ConcurrentHashMap<>();
    private ShuntSignalPolicy() {}

    public record Configuration(Map<BlockPos, List<RouteBinding>> routes, Set<BlockPos> signals) {
        public Configuration {
            final Map<BlockPos, List<RouteBinding>> copy = new HashMap<>();
            routes.forEach((pos, bindings) -> copy.put(pos, List.copyOf(bindings)));
            routes = Map.copyOf(copy);
            signals = Set.copyOf(signals);
        }
    }

    public static void publish(String dimension, Map<BlockPos, List<RouteBinding>> routes, Set<BlockPos> signals) {
        CONFIGURATIONS.put(dimension, new Configuration(routes, signals));
    }

    public static void reset() { CONFIGURATIONS.clear(); }

    public static boolean hasSignal(String dimension, BlockPos signal) {
        final Configuration config = CONFIGURATIONS.get(dimension);
        return config != null && config.signals().contains(signal);
    }

    /** Select the nearest configured destination on this directed path occurrence. */
    public static String route(String dimension, PathSnapshot path, PathSnapshot.FaceTraversal face) {
        final Configuration config = CONFIGURATIONS.get(dimension);
        // Route names remain editable after a device is removed, but cannot enable shunting alone.
        if (config == null || !config.signals().contains(face.face().signalPos())) return "";
        final List<RouteBinding> bindings = config.routes().getOrDefault(face.face().signalPos(), List.of());
        if (bindings.stream().noneMatch(binding -> RouteContent.isShunt(binding.content()))) return "";
        String best = "";
        double distance = Double.POSITIVE_INFINITY;
        final double legEnd = path.getNextTerminalNode(face.distance()).distance();
        for (final RouteBinding binding : bindings) {
            if (binding.node() == null) continue;
            for (final PathSnapshot.NodeDistance node : path.getNodeDistances(binding.node())) {
                if (node.distance() > face.distance() && node.distance() <= legEnd && node.distance() < distance) {
                    best = binding.content();
                    distance = node.distance();
                }
            }
        }
        return RouteContent.isShunt(best) ? best : "";
    }

    /** Each passed shunt entry permits one more Block; a passed main entry restores normal lookahead. */
    public static double boundary(String dimension, PathSnapshot path, List<PathSnapshot.FaceTraversal> faces, double head) {
        double limit = Double.POSITIVE_INFINITY;
        PathSnapshot.FaceTraversal previous = null;
        PathSnapshot.FaceTraversal next = null;
        for (final PathSnapshot.FaceTraversal face : faces) {
            if (!PathSnapshot.isDirectionMatched(face)) continue;
            if (face.distance() < head - 1.0E-6) {
                if (previous == null || face.distance() > previous.distance()) previous = face;
            } else {
                if (next == null || face.distance() < next.distance()) next = face;
                if (!route(dimension, path, face).isEmpty()) {
                    limit = Math.min(limit, path.getNextProtectionBoundary(face, faces).distance());
                }
            }
        }
        if (previous != null && path.getNextTerminalNode(previous.distance()).distance() >= head - 1.0E-6
                && !route(dimension, path, previous).isEmpty() && next != null) {
            limit = Math.min(limit, path.getNextProtectionBoundary(next, faces).distance());
        }
        return limit;
    }

    /** The next signal must publish this vehicle's clearance before it can be passed on the move. */
    public static PathSnapshot.FaceTraversal nextExit(String dimension, PathSnapshot path, List<PathSnapshot.FaceTraversal> faces, double head) {
        PathSnapshot.FaceTraversal previous = null;
        PathSnapshot.FaceTraversal next = null;
        for (final var face : faces) {
            if (!PathSnapshot.isDirectionMatched(face)) continue;
            if (face.distance() < head - 1.0E-6) {
                if (previous == null || face.distance() > previous.distance()) previous = face;
            } else if (next == null || face.distance() < next.distance()) next = face;
        }
        return previous != null && path.getNextTerminalNode(previous.distance()).distance() >= head - 1.0E-6
                && !route(dimension, path, previous).isEmpty() ? next : null;
    }

    /** Keep the exit face visible while stopped at its node, including a small tick overshoot. */
    public static PathSnapshot.FaceTraversal exitAtHead(String dimension, PathSnapshot path, List<PathSnapshot.FaceTraversal> faces, double head) {
        final var directedFaces = faces.stream().filter(PathSnapshot::isDirectionMatched).toList();
        for (final PathSnapshot.FaceTraversal entry : directedFaces) {
            if (route(dimension, path, entry).isEmpty()) continue;
            final var boundary = path.getNextProtectionBoundary(entry, directedFaces);
            if (!boundary.isTerminal() && boundary.distance() <= head + 1.0E-6 && head - boundary.distance() <= 0.05) {
                return boundary.face();
            }
        }
        return null;
    }
}
