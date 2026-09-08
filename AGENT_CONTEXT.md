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
- `src/main/java/org/mtrbr/server/ShuntSignalPolicy.java`: requires a bound position light and matching `shunt=` route; grants one Block only. Removing the last device restores normal signalling. Authorized trains need not stop at the entry; preserve the exit clearance publication check.
- `src/main/java/org/mtrbr/block/SpeedSignMount.java` and `render/SpeedSignRenderer.java`: decorative PSR/AWI signs. Signs have bottom/center/top mounts, arrows bottom/top only; all top mounts have full-height poles. Never change movement authority from these signs.
- `src/main/java/org/mtrbr/data/WebTokenPermissionsSavedData.java`: UUID-based generation bans stored in the overworld. Bans survive restarts but do not revoke existing tokens; token and Web dispatch operations still require OP.
- `src/main/java/org/mtrbr/client/ClientWebTokenActions.java`: explicit generate-and-open action; browser responses are correlated to a pending client request. Token replies must remain private.

## Current release and resources

The refreshed 0.1.2 uses network protocol 9. Update both server and clients, including when replacing an older 0.1.2 JAR. See `CHANGELOG.md` and `内容说明.md` for current behavior. `build_deploy_alpha4.ps1` is local-only and ignored; keep it on disk but out of commits.

`tools/generate_speed_signs.py` uses the bundled Alte DIN 1451 Mittelschrift Regular font (OFL 1.1) and rewrites only speed-sign resources and `build/previews/speed_signs.png`. `tools/check_speed_signs.py` needs Pillow. Do not regenerate unrelated hand-edited LED or position-light source models to prepare a release.

All previews belong in `build/previews/`. Restore the eight indicator sheets with `python tools/preview_indicators.py`; call `preview()` from `tools/check_position_light_signals.py` to render the position lights without regenerating authored models. Avoid `gradlew clean` when previews must be retained: it deletes the whole build directory. Preview generator scripts belong in `tools/`, not `build/`.

## Logs

Use `MTRBR-*` diagnostics, especially `MTRBR-MTR-PATH-*`, `MTRBR-OCCUPANCY-*`, `MTRBR-REQUEST-*`, `MTRBR-AUTH-*`, `MTRBR-GATE-*`, and `MTRBR-TURNBACK-*`; correlate by vehicle ID and absolute timestamp.

Avoid changes to MTR core, SectionCheck, FCFS policy, or manual route editing unless explicitly required.
