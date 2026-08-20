# MTR_BRsignal_addon Verification

Run the commands from the addon root after a successful build. Read `gradle.properties` before substituting another artifact name.

## Archive Checks

```powershell
$jar = Join-Path $PWD 'build\libs\mtr_brsignal_addon-0.1.0.jar'
Get-Item -LiteralPath $jar | Select-Object FullName, Length, LastWriteTime
jar tf $jar | Select-String -Pattern 'META-INF/mods.toml|mtrbr.mixins.json|pack.mcmeta|org/mtrbr/MTRBR.class|mtrbr.refmap.json'
```

Extract in a new temporary directory when metadata inspection is needed. Do not confuse an archive check with successful Mixin application: an in-game startup log is required for that.

For texture or model work, list exact packaged paths:

```powershell
jar tf $jar | Select-String -SimpleMatch 'assets/mtr_brsignal_addon/'
```

## Deployment Hash Check

```powershell
$source = Join-Path $PWD 'build\libs\mtr_brsignal_addon-0.1.0.jar'
$target = 'D:\Minecraft\CitiZons\10.alpha\C10.alpha.4.client\.minecraft\mods\mtr_brsignal_addon-0.1.0.jar'
Get-FileHash -LiteralPath $source -Algorithm SHA256
Get-FileHash -LiteralPath $target -Algorithm SHA256
```

Hashes must match. If they differ, do not launch the client; repeat deployment only after checking that the game is closed and the target is correct.

## Practical Regression Matrix

| Changed area | Minimum check |
|---|---|
| Java logic | `build` plus startup; exercise the affected action |
| Mixin or MTR API | startup with installed MTR 4.0.3; inspect log for injection failure |
| Packet/data sync | connected client and server with identical addon JAR; exercise send and receive paths |
| Block/entity renderer | place each changed block and view required facings, states, and distance |
| Resource/JSON/model | archive-path check plus in-game missing-texture and geometry check |
| Mod metadata/version | inspect packaged `mods.toml`; confirm Forge lists the addon |

## Known Integration Constraints

- `mods.toml` requires MTR in `[4.0,4.1)` and Forge 47+ on Minecraft 1.20.1.
- The channel protocol version is defined in `org.mtrbr.network.Network`. It was `4` in the alpha4 deployment baseline. A mismatch prevents a compatible client-server connection.
- `mtrbr.mixins.json` has `defaultRequire: 1`; a missing injection should fail visibly rather than silently disabling a signal-control hook.
- The release JAR contains compiled code and resources. Do not manually update it with loose `.class` files or resource directories; rebuild it so metadata, refmap, and contents remain consistent.
