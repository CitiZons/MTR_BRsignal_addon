package org.mtrbr.server;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/** Deterministic automatic dispatcher. Manual dispatch may replace this selector. */
public final class Dispatcher {
	private static final Comparator<RouteRequest> FCFS_ORDER = Comparator
			.comparingInt(RouteRequest::getManualPriority).reversed()
			.thenComparingDouble(RouteRequest::getRemainingPathDistance)
			.thenComparingLong(RouteRequest::getCreatedTick)
			.thenComparingLong(RouteRequest::getVehicleId);

	private Dispatcher() {
	}

	public static Optional<RouteRequest> selectFcfs(Collection<RouteRequest> requests) {
		return requests.stream()
				.filter(request -> request.getState() == RequestState.WAITING)
				.min(FCFS_ORDER);
	}
}
