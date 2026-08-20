package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Detailed, read-only check of every Section in one RouteRequest path. */
public final class SectionCheck {
	private SectionCheck() {
	}

	public static Result check(Simulator simulator, PathSnapshot path, long vehicleId, String ownerId, boolean manualDrivingOverride) {
		return check(simulator, path.matchesTopology(simulator), path.getSections().stream().map(PathSnapshot.PathSection::sectionId).toList(), vehicleId, ownerId, manualDrivingOverride);
	}

	public static Result check(Simulator simulator, boolean topologyValid, List<String> sectionIds, long vehicleId, String ownerId, boolean manualDrivingOverride) {
		final Map<String, SectionStateManager.SectionSnapshot> states = SectionStateManager.getSections(simulator, sectionIds);
		final List<SectionResult> results = new ArrayList<>();
		boolean safe = topologyValid;
		for (final String sectionId : sectionIds) {
			final SectionStateManager.SectionSnapshot state = states.get(sectionId);
			if (!topologyValid) {
				results.add(new SectionResult(sectionId, Status.TOPOLOGY_INVALID, state, "Path fingerprint does not match current Rail topology"));
				continue;
			}
			if (state == null || !state.exists) {
				results.add(new SectionResult(sectionId, Status.MISSING, state, "Section does not exist"));
				safe = false;
			} else if (state.lockedBy.stream().anyMatch(owner -> !owner.equals(ownerId))) {
				results.add(new SectionResult(sectionId, Status.LOCKED, state, "Section is locked by another route"));
				safe = false;
			} else if (state.reservedBy.stream().anyMatch(owner -> !owner.equals(ownerId))) {
				results.add(new SectionResult(sectionId, Status.RESERVED, state, "Section is reserved by another request"));
				safe = false;
			} else if (!manualDrivingOverride && state.occupiedBy.stream().anyMatch(vehicle -> vehicle != vehicleId)) {
				results.add(new SectionResult(sectionId, Status.OCCUPIED, state, "Section is occupied"));
				safe = false;
			} else if (manualDrivingOverride && !state.occupiedBy.isEmpty()) {
				results.add(new SectionResult(sectionId, Status.OVERRIDE_OCCUPIED, state, "Manual driving override bypasses occupied state"));
			} else {
				results.add(new SectionResult(sectionId, Status.AVAILABLE, state, ""));
			}
		}
		return new Result(safe, results);
	}

	public enum Status {
		AVAILABLE,
		OCCUPIED,
		OVERRIDE_OCCUPIED,
		RESERVED,
		LOCKED,
		MISSING,
		TOPOLOGY_INVALID
	}

	public record SectionResult(String sectionId, Status status, SectionStateManager.SectionSnapshot state, String reason) {
	}

	public record Result(boolean safe, List<SectionResult> sections) {
		public Result {
			sections = List.copyOf(sections);
		}
	}
}
