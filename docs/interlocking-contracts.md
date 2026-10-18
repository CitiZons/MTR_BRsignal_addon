# Interlocking ownership and verification

## Responsibilities

- `RouteRequestManager` preserves the public API and coordinates simulation observations, request arbitration, confirmed removal, dispatcher actions and reversal completion.
- `RouteRuntime` holds simulation-thread state and typed internal projection results. Integration fixtures construct these types directly; runtime storage is not exposed to clients or Web handlers.
- `RouteAuthorizationPlanner` calculates fixed-block clearance and operational boundaries without acquiring resources.
- `RouteAuthorizationLifecycle` owns authorization acquisition, extension, activity refresh and retirement.
- `RouteTurnback` observes reversal intent, projects directed occurrence fences and tracks handoff phases. Completing a reversal and refreshing authorization is coordinated by `RouteRequestManager`, so the phase service does not call back into the authorization lifecycle.
- `RouteMovementControl` calculates stop boundaries; `RouteOverride` owns one-shot permits and their expiry. Neither arbitrates pending route requests.
- `RouteDispatchSnapshots` publishes immutable signal/dispatcher views and computes display metadata. `RouteDiagnostics` contains route and resource diagnostics.
- `RouteVehicleCodes` synchronizes allocation, reverse lookup, removal and reset across simulation and server/Web callers.
- `AuthorizationResources` acquires one already validated resource set. It snapshots prior ownership before acquisition and rolls back only newly acquired resources on failure. It does not choose paths, change request state or override occupancy checks.
- `RetainedProtection` owns a vehicle's pending physical protection after movement authority is retired. Its mutable section/block maps are private. Protection is independent of the current request; recorded owners survive request replacement and stale-resource sweeps.
- `TurnbackHandoff` owns the native-termination/reacquisition phase and its clocks. Callers report native reversal, valid activity, failure and successful grant; callers cannot independently write phase flags and timestamps.
- `RouteProjection` remains the source for fixed-block projection. Unused private saved-block projection code was removed from `RouteRequestManager` rather than maintaining a second acceptance path.

## Contracts

1. A short movement-authority prefix still protects every section of its physical saved block.
2. Pending protection clears only after physical section occupancy clears. Request end distance, path refresh and missing observations do not prove clearance.
3. Canonical block keys and junction resources shared by several active/pending occurrences remain held until the final reference clears.
4. Releasing an old owner cannot unlock a new owner's resources. Same-train ownership transfer happens before arbitration; cross-train transfer is rejected.
5. Failed acquisition releases new section, block, junction and zone reservations while preserving previously owned resources. Undefined zone definitions reject acquisition without throwing or acquiring resources.
6. Native termination allows 1200 ticks. The separate 200-tick reacquisition clock starts when reversal is observed, excluding door/dwell time. Failed or timed-out handoffs do not defer the gate.
7. Confirmed physical removal clears protection through one shared cleanup path. Existing terminal request states are preserved; live requests are canceled. This applies to MTR callbacks, manual removal and quarantine confirmation.
8. Server shutdown discards published runtime state, vehicle codes and queued topology-mapping retries.
9. Retired requests keep their terminal state during topology invalidation; their retained physical protection still drains to RELEASED after confirmed clearance. Dispatcher approval, revocation and override admission reject terminal requests before mutating authority. Manual approval checks topology and follows CHECKING -> WAITING -> AUTHORIZED (or DENIED when no clearance exists).
10. Junction request-to-vehicle metadata exists only while a reservation or lock references the request. Partial release preserves it; final release, owner transfer, failed/empty acquisition and stale cleanup reclaim unreferenced entries. Retained physical locks count as live references.
11. Dispatcher audit history retains the latest 256 events per simulator in chronological order. Immutable published lists are reused until an event changes the history; older published lists remain unchanged.
12. A tracked vehicle's confirmed-removal event is consumed only by its owning simulator. An unrelated dimension's earlier tick cannot discard the event, vehicle code or occupancy.

## Verification

Run from the project root:

```powershell
.\gradlew.bat routeReviewRegression interlockingRegression turnbackRegression --offline
.\gradlew.bat build --offline --no-daemon
.\tools\check_regressions.ps1
git diff --check
```

`interlockingRegression` is part of `check`/`build`. Its contract cases call the production components and resource managers directly, without private-state reflection:

- 7 retained-protection cases;
- 4 native-turnback handoff scenarios;
- 6 acquisition/rollback cases, including preservation of earlier zone locks;
- concurrent vehicle-code allocation, reverse lookup and release across 512 requests for 64 vehicles.

`turnbackRegression` also runs the lifecycle integration scenarios. Removal coverage includes three entry points times six request states, plus unrelated-dimension tick ordering. Both lifecycle and turnback-projection fixtures use typed path/state construction and direct service calls, with no private reflection. Signal aspect and binding persistence fixtures use the same typed publication/deserialization operations as production.

The public static method signatures and public record types of `RouteRequestManager` remain unchanged for mixins, packets, WebUI and downstream addons. Route-service operation dependencies are acyclic: planners depend on phase projection and diagnostics; lifecycle depends on planning and permit retirement; reversal completion stays in the coordinator. Services do not call the public facade to perform their own work.

These checks establish the source-level contracts above. Long-running multiplayer, chunk-unload and actual Minecraft rendering acceptance require a separate game session; passing this suite is not a claim that every possible mod interaction has been validated.

## Acceptance result — 2026-10-18

- `gradlew.bat build --offline --no-daemon`: successful, including 22 LINE-path cases, 13 turnback-projection cases, 15 lifecycle scenarios, the component contracts above, Web sessions, sensors and resource verification.
- `tools/check_regressions.ps1` and `git diff --check`: passed.
- Public route facade method signatures: unchanged. Route service operation dependency graph: acyclic. Lifecycle and turnback-projection fixture reflection calls: zero.
- The unrelated-dimension removal regression failed before the ownership check and passed after it. Final build output: `build/final-architecture-build.log`.
- `RouteRequestManager` is a facade/coordinator; resource planning, lifecycle, phase projection, permits, gate calculation, snapshots and diagnostics have explicit component owners.

## Follow-up review coverage — 2026-10-18

`routeReviewRegression` is part of `check`/`build`. The initial ten cases produced nine failures before the fixes, reproducing terminal transition failures, junction metadata retention and audit growth/copying. The final 15 cases also cover terminal approval/override, premature approval/revocation, stale-topology approval and a denied request retrying through legal transitions to a real reserved/locked authorization. These fixtures use typed production operations without private reflection.

Follow-up acceptance: `gradlew.bat build --offline --no-daemon` passed with all 15 review cases plus existing lifecycle, turnback, interlocking, LINE, Web, sensor and resource checks (`build/final-review-build.log`). `tools/check_regressions.ps1` and `git diff --check` passed. Final source review checked command preflight, terminal cleanup ordering, exact-owner release/transfer and immutable audit publication; no further actionable defect was found in these reviewed paths. No commit, push or deployment was performed.

## 0.2.1 verification — 2026-10-18

The maintainer reported no obvious issues in an in-game check of the preceding fixes. Version 0.2.1 keeps network protocol 11 and the existing Minecraft/Forge/MTR requirements. The versioned full build passed, including all 15 follow-up review cases and the existing regression suite (`build/release-0.2.1-build.log`); static regression checks passed as well.
