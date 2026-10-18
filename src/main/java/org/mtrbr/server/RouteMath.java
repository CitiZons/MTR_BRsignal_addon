package org.mtrbr.server;

/** Shared route distance, angle and diagnostic formatting helpers. */
final class RouteMath {
	private RouteMath() {}

	static double angleDifference(double first, double second) {
		if (Double.isNaN(first) || Double.isNaN(second)) return 180;
		double difference = Math.abs((first - second) % 360);
		return difference > 180 ? 360 - difference : difference;
	}

	static double finiteOr(double value, double fallback) {
		return Double.isFinite(value) ? value : fallback;
	}

	static boolean rangesIntersect(double firstStart, double firstEnd, double secondStart, double secondEnd) {
		return secondEnd > firstStart + 1.0E-6 && secondStart < firstEnd - 1.0E-6;
	}

	static String fmt(double value) {
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}

	static double normalizeAngle(double angle) {
		double normalized = angle % 360;
		return normalized < 0 ? normalized + 360 : normalized;
	}
	static int currentTraversalIndex(PathSnapshot path, double distance) {
		for (final PathSnapshot.PathTraversal traversal : path.getTraversals()) {
			if (distance < traversal.endDistance() - 1.0E-6) return traversal.index();
		}
		return Math.max(0, path.getTraversals().size() - 1);
	}
}
