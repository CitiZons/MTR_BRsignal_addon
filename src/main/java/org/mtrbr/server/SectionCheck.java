package org.mtrbr.server;

import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Detailed, read-only check of every Section in one RouteRequest path. */
public final class SectionCheck {
	private static final Map<Simulator, Map<String, String>> LAST_LOCK_AUDITS = new IdentityHashMap<>();

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
			} else if (state.occupiedBy.stream().anyMatch(vehicle -> vehicle != vehicleId)) {
				results.add(new SectionResult(sectionId, Status.OCCUPIED, state, "Section is occupied"));
				safe = false;
			} else {
				results.add(new SectionResult(sectionId, Status.AVAILABLE, state, ""));
			}
		}
		return new Result(safe, results);
	}

	/** Atomic check for one persisted A->B SignalBlock. Every Rail and block resource must be free. */
	public static BlockResult checkBlock(Simulator simulator, String blockId, List<String> railIds, long vehicleId, String ownerId, boolean manualDrivingOverride) {
		final Result sections = check(simulator, true, railIds, vehicleId, ownerId, manualDrivingOverride);
		for (final SectionResult result : sections.sections()) {
			if (result.status() == Status.LOCKED && result.state() != null) {
				final String lockAudit = "block=" + blockId + " rail=" + result.sectionId() + " lockedBy=" + result.state().lockedBy;
				final String auditKey = blockId + "|" + result.sectionId();
				final Map<String, String> simulatorAudits = LAST_LOCK_AUDITS.computeIfAbsent(simulator, ignored -> new HashMap<>());
				if (!lockAudit.equals(simulatorAudits.put(auditKey, lockAudit))) {
					MtrbrDebugLog.event("LOCK-OWNER", lockAudit);
					System.out.println("[MTRBR-LOCK-OWNER] " + lockAudit);
				}
			}
		}
		final boolean blockAvailable = SectionStateManager.areBlocksAvailable(simulator, List.of(blockId), ownerId);
		final Status status;
		if (!sections.safe()) {
			status = sections.sections().stream().map(SectionResult::status).filter(item -> item != Status.AVAILABLE).findFirst().orElse(Status.OCCUPIED);
		} else if (!blockAvailable) {
			status = Status.BLOCK_CONFLICT;
		} else {
			status = Status.AVAILABLE;
		}
		return new BlockResult(blockId, railIds, status, sections, blockAvailable && sections.safe());
	}

	public enum Status {
		AVAILABLE,
		OCCUPIED,
		RESERVED,
		LOCKED,
		MISSING,
		TOPOLOGY_INVALID,
		BLOCK_CONFLICT
	}

	public record SectionResult(String sectionId, Status status, SectionStateManager.SectionSnapshot state, String reason) {
	}

	public record Result(boolean safe, List<SectionResult> sections) {
		public Result {
			sections = List.copyOf(sections);
		}
	}

	public record BlockResult(String blockId, List<String> sectionIds, Status status, Result sections, boolean safe) {
		public BlockResult {
			sectionIds = List.copyOf(sectionIds);
		}
	}
}
