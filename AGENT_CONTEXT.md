# AGENT_CONTEXT.md

## Start here

This is the Forge 1.20.1 `MTR_BRsignal_addon` mod for MTR 4.0.3. It adds British-style signal aspects, fixed-block interlocking, route requests, authorization, section/block/junction locking, depot LINE path editing, and dispatcher/Web diagnostics.

## Build and change discipline

Run `./gradlew build --no-daemon` from this directory with Java 17. The build includes regression fixtures. Preserve unrelated uncommitted work; do not reset/clean the worktree. Do not commit, push, publish releases, deploy JARs, or modify saves unless explicitly requested.

MTR `immutablePath` is the physical source of truth. Requests describe the complete physical route; authorization is only a safe movable prefix. Empty paths and missing observations are transient until MTR explicitly confirms vehicle removal; fail closed. Keep native depot generation and existing manual LINE integration intact.

## Important files

- `src/main/java/org/mtrbr/server/RouteRequestManager.java`: public facade and tick/arbitration coordinator. Preserve its public signatures and nested public record types.
- `src/main/java/org/mtrbr/server/RouteRuntime.java`: typed simulation state shared by package-internal route services. Integration fixtures use these types directly.
- `src/main/java/org/mtrbr/server/RouteAuthorizationPlanner.java` and `RouteAuthorizationLifecycle.java`: separate read-only clearance planning from authorization mutation and retirement.
- `src/main/java/org/mtrbr/server/RouteTurnback.java`, `RouteMovementControl.java`, `RouteOverride.java`, `RouteDispatchSnapshots.java`, `RouteDiagnostics.java`, `RouteVehicleCodes.java`: phase projection, gate boundaries, permit lifetime, immutable projections, diagnostics and synchronized vehicle-code ownership. Reversal completion stays in the coordinator to avoid a service dependency cycle.
- `src/main/java/org/mtrbr/server/RetainedProtection.java`: encapsulated physical protection retained after authorization retirement; exact-owner and shared-resource release.
- `src/main/java/org/mtrbr/server/AuthorizationResources.java`: resource acquisition and rollback of only newly acquired resources.
- `src/main/java/org/mtrbr/server/TurnbackHandoff.java`: native reversal handoff phases and separate termination/reacquisition clocks.
- `docs/interlocking-contracts.md`: component boundaries, invariants and verification commands. `interlockingRegression` runs direct component tests as part of `check`/`build`; lifecycle and turnback-projection fixtures use typed services and publication boundaries without reflection.
- `src/main/java/org/mtrbr/server/SectionStateManager.java`: physical occupancy and section lifecycle.
- `src/main/java/org/mtrbr/server/PathSnapshot.java`: immutablePath projection and boundaries.
- `src/main/java/org/mtrbr/server/MovementGate.java`: runtime movement veto.
- `src/main/java/org/mtrbr/server/SignalTopology.java`: persisted signal/node topology.
- `src/main/java/org/mtrbr/web/DepotPathEditorService.java`: LINE path validation/rebuild.
- `src/main/java/org/mtrbr/mixin/RenderSignalBaseMixin.java`: expanded native client node lookup.
- `src/main/java/org/mtrbr/server/ShuntSignalPolicy.java`: requires a bound position light and matching `shunt=` route. Passing an entry immediately permits the next one-Block request; consecutive shunts advance the window only after each node is passed. Passing an ordinary main-signal node restores normal lookahead. Removing the last device restores normal signalling. Preserve clearance publication checks on approach and at the exit.
- `src/main/java/org/mtrbr/block/SpeedSignMount.java` and `render/SpeedSignRenderer.java`: decorative PSR/AWI signs. Signs have bottom/center/top mounts, arrows bottom/top only; all top mounts have full-height poles. Never change movement authority from these signs.
- `src/main/java/org/mtrbr/data/WebTokenPermissionsSavedData.java`: UUID-based generation bans stored in the overworld. Bans survive restarts but do not revoke existing tokens; token and Web dispatch operations still require OP.
- `src/main/java/org/mtrbr/client/ClientWebTokenActions.java`: explicit generate-and-open action; browser responses are correlated to a pending client request. Token replies must remain private.

## Current release and resources

The current build is 0.2.1 and uses network protocol 11. Minecraft 1.20.1, Forge 47.4.18 and MTR Forge 4.0.3 requirements are unchanged. Install the same addon build on server and clients. See `CHANGELOG.md` and `内容说明.md` for current behavior. `build_deploy_alpha4.ps1` is local-only and ignored; keep it on disk but out of commits.

`tools/generate_speed_signs.py` uses the bundled Alte DIN 1451 Mittelschrift Regular font (OFL 1.1) and rewrites only speed-sign resources and `build/previews/speed_signs.png`. `tools/check_speed_signs.py` needs Pillow. Do not regenerate unrelated hand-edited LED or position-light source models to prepare a release.

All previews belong in `build/previews/`. Restore the eight indicator sheets with `python tools/preview_indicators.py`; call `preview()` from `tools/check_position_light_signals.py` to render the position lights without regenerating authored models. Avoid `gradlew clean` when previews must be retained: it deletes the whole build directory. Preview generator scripts belong in `tools/`, not `build/`.

The 62 `textures/block/path/path_*.png` textures are hand-refined 21x21 Blockbench artwork. Preserve individual strokes and symbol distinctions; do not batch-rescale or regenerate them. `python tools/preview_path_textures.py` reads these files and writes `build/previews/path-textures.png` without editing the originals. Resource checks enforce the 21x21 size.

## Logs

Signal aspects are evaluated every 10 server ticks; changed displays synchronize immediately, with a 20-tick fallback. Dispatcher/Web snapshots remain on the 20-tick schedule. `gradlew build` includes non-generating Python resource checks; install `tools/requirements.txt` and optionally set `-PpythonExecutable=...`.

Use `MTRBR-*` diagnostics, especially `MTRBR-MTR-PATH-*`, `MTRBR-OCCUPANCY-*`, `MTRBR-REQUEST-*`, `MTRBR-AUTH-*`, `MTRBR-GATE-*`, and `MTRBR-TURNBACK-*`; correlate by vehicle ID and absolute timestamp.

Avoid changes to MTR core, SectionCheck, FCFS policy, or manual route editing unless explicitly required.

## Follow-up runtime contracts

Terminal requests must not be reactivated by topology refresh or dispatcher commands. Retained occupied resources still clear after physical clearance, including when the old path topology no longer matches. Manual approval follows the existing request transition table and validates topology before changing state or acquiring resources.

Junction owner metadata must be reclaimed when no reservation/lock references its request; pending physical locks keep the mapping alive. Dispatcher audit history retains only the latest 256 entries and reuses its immutable snapshot until changed. `routeReviewRegression` covers these behaviors and runs with `check`/`build`.
