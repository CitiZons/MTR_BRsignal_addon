package org.mtrbr.server;

import java.util.ArrayDeque;
import java.util.List;

/** Simulation-thread recent history; published snapshots are immutable and reusable. */
final class RouteAuditLog {
	private static final int LIMIT = 256;
	private final ArrayDeque<String> entries = new ArrayDeque<>();
	private List<String> published = List.of();

	void add(String event) {
		if (entries.size() == LIMIT) entries.removeFirst();
		entries.addLast(event);
		published = null;
	}

	List<String> snapshot() {
		if (published == null) published = List.copyOf(entries);
		return published;
	}
}
