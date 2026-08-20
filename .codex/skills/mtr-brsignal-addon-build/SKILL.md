---
name: mtr-brsignal-addon-build
description: Build, inspect, package, and deploy the MTR_BRsignal_addon Forge 1.20.1 mod to the CitiZons alpha4 client. Use for source changes, JAR verification, or alpha4 mod replacement; do not use for unrelated Forge projects.
---

# MTR BRsignal Addon Build

Use this skill for the repository containing this file. The project is a Forge 1.20.1 addon that uses Mixins against MTR. A successful Gradle build proves compilation only; inspect the final archive and perform a client launch test when behavior or compatibility changed.

## Working Rules

- Preserve existing working-tree changes. Do not clean `build/`, the alpha4 mods directory, or Gradle caches merely to retry a build.
- Read `build.gradle`, `gradle.properties`, `src/main/resources/META-INF/mods.toml`, `mtrbr.mixins.json`, and `build_deploy_alpha4.ps1` before changing build or deployment behavior. Version and archive names are properties, not constants to retype.
- This baseline uses Java 17, Minecraft 1.20.1, Forge 47.4.18, MTR Forge 4.0.3, and MTR's local JAR in `libs/`. Confirm the actual configured inputs before diagnosing failures.
- Make the deployment JAR name match the built archive name. Do not retain another older addon JAR with the same `modId` in the client `mods` folder.

## Build And Package

1. From the addon root, verify `libs/MTR-forge-<mtr_version>+<minecraft_version>.jar` exists and that the configured Java resolves to 17. Use the Gradle wrapper where usable; use the locally configured Gradle distribution only when wrapper bootstrap is unavailable or already known to fail.
2. Run a full build with the daemon disabled: `gradlew.bat build --no-daemon`. Capture the first meaningful compiler, resource-processing, Mixin, or dependency error rather than retrying unchanged.
3. Resolve the expected output from `mod_id` and `mod_version` in `gradle.properties`, then confirm `build/libs/<mod_id>-<mod_version>.jar` exists and is nonempty. Do not deploy `-sources.jar`, `-dev.jar`, or a stale archive.
4. Inspect the archive after changes to code, resources, metadata, Mixins, or dependencies. Check required class and resource entries plus `META-INF/mods.toml`, `mtrbr.mixins.json`, and `pack.mcmeta`. Read [verification.md](references/verification.md) for PowerShell checks and interpretation.

## Code And Compatibility Verification

Apply checks proportional to the change:

- Resource or model changes: verify changed asset paths are inside the JAR and match the namespace used by Java and JSON.
- Registry changes: confirm registered block/item/block-entity IDs match blockstate, model, language, and texture paths.
- Network changes: packet discriminators must remain unique and both ends must use the same `PROTOCOL_VERSION`; incompatible changes require replacing both client and server JARs.
- Mixin changes: ensure each class is listed on the correct `mixins` or `client` side, target signatures match the installed MTR JAR, and the refmap is packaged. Keep `defaultRequire: 1` unless there is an explicit, tested compatibility reason to relax it.
- Signalling changes: retain the server-authoritative boundary. Route requests and section/authorization state are computed server-side; client code renders synchronized state. Do not claim Movement Gate behavior is complete without an in-game demonstration.

For a functional smoke test, use a temporary or backed-up test world and check startup, mod loading, creative registration, block placement, indicator rendering at required rotations, tool binding/synchronization, and client-server connection. Confirm the log has no Mixin application, missing-resource, or packet decode error.

## Deploy To Alpha4

The intended client is `D:\Minecraft\CitiZons\10.alpha\C10.alpha.4.client\.minecraft\mods`. Close the game before replacement. Copy the verified release JAR to that folder with its exact built filename, overwriting only that target. Then compare source and deployed SHA-256 hashes and report the deployed path, archive size, and hash match.

`build_deploy_alpha4.ps1` is the preferred reusable deployment entry point after confirming it reflects the current Gradle location, archive properties, and alpha4 path. Keep it property-driven when updating it; do not hard-code a guessed versioned JAR name.

Before starting the client, ensure exactly one JAR provides `mtr_brsignal_addon`. If a matching dedicated server is used, install the identical verified archive there too because the Forge channel protocol is strict.

## Failure Boundaries

- Stop deployment when Gradle fails, archive inspection fails, the target client path is absent, or a running game may lock the JAR. Explain the failed gate and preserve the previous target JAR.
- Do not overwrite a server, launcher profile, game save, MTR dependency, or another mod as a workaround.
- If the project version, alpha4 directory, Gradle distribution, or MTR dependency differs from this record, discover the current value from project configuration and report the divergence before modifying automation.
