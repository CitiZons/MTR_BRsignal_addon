# AGENT_CONTEXT.md

## Start here

This is the Forge 1.20.1 `MTR_BRsignal_addon` mod for MTR 4.0.3. It adds British-style signal aspects, fixed-block interlocking, route requests, authorization, section/block/junction locking, depot LINE path editing, and dispatcher/Web diagnostics.

## Build and change discipline

Run `./gradlew build --no-daemon` from this directory with Java 17. The build includes regression fixtures. Preserve unrelated uncommitted work; do not reset/clean the worktree. Do not commit, push, publish releases, deploy JARs, or modify saves unless explicitly requested.

MTR `immutablePath` is the physical source of truth. Requests describe the complete physical route; authorization is only a safe movable prefix. Empty paths and missing observations are transient until MTR explicitly confirms vehicle removal; fail closed. Keep native depot generation and existing manual LINE integration intact.

## Important files

- `src/main/java/org/mtrbr/server/RouteRequestManager.java`: request, authorization, lifecycle, dispatcher snapshots.
- `src/main/java/org/mtrbr/server/SectionStateManager.java`: physical occupancy and section lifecycle.
- `src/main/java/org/mtrbr/server/PathSnapshot.java`: immutablePath projection and boundaries.
- `src/main/java/org/mtrbr/server/MovementGate.java`: runtime movement veto.
- `src/main/java/org/mtrbr/server/SignalTopology.java`: persisted signal/node topology.
- `src/main/java/org/mtrbr/web/DepotPathEditorService.java`: LINE path validation/rebuild.
- `src/main/java/org/mtrbr/mixin/RenderSignalBaseMixin.java`: expanded native client node lookup.

## Logs

Use `MTRBR-*` diagnostics, especially `MTRBR-MTR-PATH-*`, `MTRBR-OCCUPANCY-*`, `MTRBR-REQUEST-*`, `MTRBR-AUTH-*`, `MTRBR-GATE-*`, and `MTRBR-TURNBACK-*`; correlate by vehicle ID and absolute timestamp.

Avoid changes to MTR core, SectionCheck, FCFS policy, or manual route editing unless explicitly required.
