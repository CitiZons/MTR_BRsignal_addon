package org.mtrbr.server;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/** Deterministic automatic dispatcher. Manual dispatch may replace this selector. */
public final class Dispatcher {
	private static final Comparator<RouteRequest> FCFS_ORDER = Comparator
			.comparingLong(RouteRequest::getCreatedTick)
			.thenComparingLong(RouteRequest::getVehicleId);

	private Dispatcher() {
	}

	public static Optional<RouteRequest> selectFcfs(Collection<RouteRequest> requests) {
		return requests.stream()
				// The same FCFS order arbitrates both first grants and progressive
				// Authorization extensions.  An ACTIVE request is intentionally put
				// in this collection when its current prefix needs another Block.
				.filter(request -> request.getState() == RequestState.WAITING
						|| request.getState() == RequestState.AUTHORIZED
						|| request.getState() == RequestState.ACTIVE)
				.min(FCFS_ORDER);
	}
}
