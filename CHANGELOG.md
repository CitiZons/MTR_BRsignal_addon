# 更新记录 / Changelog

## 0.2.3-dev.1 (2026-11-13)

本开发版继续使用 Minecraft 1.20.1、Forge 47.4.18、MTR Forge 4.0.3 及网络协议 `11`。客户端与服务端使用同一 `mtr_brsignal_addon-0.2.3-dev.1.jar`。

This development build keeps Minecraft 1.20.1, Forge 47.4.18, MTR Forge 4.0.3 and network protocol `11`. Install the same `mtr_brsignal_addon-0.2.3-dev.1.jar` on the client and server.

### Web UI 完成项 / Web UI Completion

- 完成控制台风格 Web UI 重构，加入 IBM Plex Sans/Mono 字体、响应式面板、状态色、地图叠加层和可访问焦点样式。
- 补齐英文、简体中文、繁体中文和日文的品牌、传感器绑定及车辆隔离控制文案。
- 修复保存信号、线路节点和确认移除列车时网络错误未处理的问题。
- 修复 UI 缩放对布局列宽的重复放大，避免高缩放比例下地图区域被压缩到不可用。
- 新增基于 lieflat-charts 纸张、墨色和细分隔线语法的白天版布局，保留原夜间版并支持一键切换；地图、传感器、信号、线路和车辆状态会同步换肤。

The Web UI refinement now includes the control-room layout, bundled IBM Plex fonts, localized sensor and vehicle controls, resilient network error handling for editing actions, corrected high-scale layout sizing, and a switchable lieflat-inspired daytime surface while preserving the original night surface.

### 验证 / Validation

`./gradlew.bat build --no-daemon`、静态回归检查、JavaScript 语法检查及 `git diff --check` 通过。传感器 Playwright QA 未执行，因为当前环境未安装 `playwright` 模块。

`./gradlew.bat build --no-daemon`, static regression checks, JavaScript syntax checks and `git diff --check` pass. The sensor Playwright QA was not run because the current environment does not have the `playwright` module installed.

## 0.2.2 (2026-11-03)

Minecraft 1.20.1、Forge 47.4.18、MTR Forge 4.0.3 及网络协议 `11` 不变。作者标识更新为 `7icence1Z`，客户端与服务端使用 `mtr_brsignal_addon-0.2.2.jar`。

Minecraft, Forge, MTR requirements and network protocol `11` are unchanged. The author metadata is `7icence1Z`; install `mtr_brsignal_addon-0.2.2.jar` on both client and server.

### 新增与调整 / Added and Updated

- Web UI 左侧抽屉底部增加两行语言选择：`English`、`简体中文`、`繁體中文`、`日本語`。按钮名称固定使用对应语言名称，不随当前界面语言改名。
- 新增 50%–200% 的 UI 缩放滑条，保存于浏览器本地设置。缩放覆盖顶部标题、左右抽屉、按钮、弹窗、图例、状态栏及地图上的 UI 覆盖层；中心地图画布、坐标和地图交互比例保持不变。
- Web UI 中文使用 Sarasa Gothic CL，香港繁体中文使用 Sarasa Gothic HC，日语使用 Sarasa Gothic J；字体资源与上游来源记录加入第三方声明。
- 更新中文、香港繁体中文和日语文案，统一采用区段、信号面、锁闭、授权、接近区段、目标区段和调度操作等铁路调度术语。

The Web UI adds a two-row language selector with fixed native language names and a 50%–200% UI scale control. Scaling applies to interface chrome and overlays while leaving the map canvas unchanged. Sarasa Gothic CL, HC and J are bundled for Simplified Chinese, Hong Kong Traditional Chinese and Japanese. Dispatcher terminology is updated for the three localized modes.

### 修复 / Fixed

- 修复语言选择按钮在当前语言切换后显示成其他语言名称的问题。
- 修复 UI 缩放时请求和线路抽屉竖条因重复计算 `left` 偏移而进入抽屉或地图的问题；抽屉与竖条现在使用同一定位基准。
- 修复 BRsignal Web UI 只读快照接口未验证 token/device 的问题；无有效会话时返回 `AUTH_REQUIRED`。
- 修复不存在车辆也能进入 Web 隔离流程的问题。
- 修复 Web 快照请求重叠时旧响应覆盖新响应的问题。

Language buttons retain their native names. Drawer toggles use one positioning basis at every scale. Web read-only snapshots require the existing token/device session, quarantine requires a real vehicle snapshot, and overlapping refresh requests are suppressed.

### 验证 / Validation

`processResources`、Java 编译、JavaScript 语法检查及 `git diff --check` 通过。字体资源为 Sarasa Gothic 1.0.43 的 CL/HC/J regular 字体，第三方许可记录已更新。未执行 commit。

Resource processing, Java compilation, JavaScript syntax checks and `git diff --check` pass. Sarasa Gothic 1.0.43 CL/HC/J regular fonts are recorded in the third-party notices. No commit was created.

## 0.2.1 (2026-10-18)

Minecraft 1.20.1、Forge 47.4.18、MTR Forge 4.0.3 及网络协议 `11` 不变；客户端与服务端使用 `mtr_brsignal_addon-0.2.1.jar`。

Minecraft, Forge, MTR requirements and network protocol `11` are unchanged. Install `mtr_brsignal_addon-0.2.1.jar` on both client and server.

### 新增与调整 / Added and Updated

- `RouteRequestManager` 从同时处理规划、授权和诊断的控制器拆为协调入口；`RouteRuntime` 保存运行状态，`RouteAuthorizationPlanner` 计算授权范围，`RouteAuthorizationLifecycle` 执行资源与授权变更，`RouteTurnback`、`RouteMovementControl`、`RouteOverride`、`RouteDispatchSnapshots`、`RouteDiagnostics` 分别负责折返、停车边界、越红灯许可、快照和诊断。保留原有 public static 方法签名及公共 record 类型。
- 资源申请与保留保护分别交由 `AuthorizationResources` 和 `RetainedProtection` 管理；折返阶段及计时交由 `TurnbackHandoff` 管理。原生终到阶段等待上限为 1200 tick，观察到反向后另起 200 tick 的重新授权计时，停车开门时间不计入后者。
- 车辆短码的分配、反查、释放和重置统一由同步的 `RouteVehicleCodes` 管理；并发调用为同一车辆返回同一码，不同车辆不共用短码。
- 调度审计由无上限列表改为 `RouteAuditLog`：每个模拟器只保留最近 256 条，按旧到新排列；新增记录时淘汰最旧记录，未新增时复用同一不可变快照，旧快照不会随后续写入改变。

Route management is split into named planning, lifecycle, turnback, gate, permit, snapshot and diagnostic components without changing the public facade signatures. Resource acquisition, retained protection and handoff clocks have separate owners. Native termination and post-reversal reacquisition use independent 1200/200-tick deadlines. Vehicle-code operations are synchronized. Audit history changes from unbounded storage to the latest 256 entries per simulator, with immutable snapshots reused until the next event.

### 修复 / Fixed

- **终态遇到拓扑变化**：旧路径拓扑失配时，原先会尝试将 `CANCELED` 等终态改为 `INVALID` 并抛出非法转换异常。`invalidateAuthorization` 现在保留 `CANCELED / REVOKED / INVALID / PASSED / RELEASED`；tick 在检查旧路径拓扑前处理终态清理，物理占用清空后可进入 `RELEASED`，不再被旧拓扑阻断。
- **调度命令修改终态请求**：撤销原先可能先清除授权、再因状态转换非法而抛异常。撤销和人工批准现在先返回 `request_terminal`，不修改已有授权；`NONE / APPROACHING` 状态返回 `request_state`。人工批准遇到失配拓扑返回 `topology_changed`；可重试请求沿 `CHECKING -> WAITING -> AUTHORIZED` 取得锁闭，无可用区段时进入 `DENIED`。一次性越红灯入口及许可有效性检查也拒绝终态请求。
- **占用保护与资源交接**：授权失效、运行距离重置或请求替换后，以仍占用的 Section 集合保留整个 Block 的区段、canonical Block、occurrence 和道岔锁。释放时使用记录的原所有者；同车请求先转移所有者再参与仲裁，旧所有者不能解锁新所有者的资源。共享 canonical Block 和道岔仅在最后一个 active/pending 引用清除后释放，不能仅凭路径终点距离释放。
- **申请失败回滚**：`AuthorizationResources.reserveAndLock` 先记录已有资源；区段、闭塞、道岔或单线区申请失败时只释放本次新增资源，保留同一请求此前持有的预留与锁闭。
- **移除清理与维度顺序**：MTR 确认移除、人工移除、隔离确认共用 `cleanupConfirmedRemoval`；保留已终结请求状态，取消仍存活的请求并释放资源。若确认移除事件对应的车辆仍由另一模拟器跟踪，当前维度不消费该事件，由所属模拟器清理，避免先 tick 的无关维度丢弃事件。
- **关闭后的重试残留**：`RouteRequestManager.resetAll` 现在同时清空 `PENDING_MAPPING_RETRIES`，避免上次运行排队的保护映射重试带入下一次运行。
- **缺失单线区定义**：`CapacityLeaseManager.areZonesAvailable` 查询不存在的 zone ID 时返回不可用，替代解引用缺失定义导致的空指针异常；申请被拒绝且不取得资源。
- **道岔所有者记录泄漏**：`JunctionStateManager.REQUEST_VEHICLES` 原先只在全局重置时清空。现在在部分/全部释放、所有者交接、过期清理及空/失败申请后，按实际 `reservedBy / lockedBy` 引用回收记录；最后一份资源释放即删除映射，仍有保留物理锁时保留映射。失败预检查不再创建空道岔资源记录。

Topology invalidation no longer attempts illegal transitions on terminal requests, and stale paths no longer prevent their physical cleanup. Dispatcher rejection occurs before authorization mutation, with explicit `request_terminal`, `request_state` and `topology_changed` results. Retained resources use physical occupancy and recorded ownership; shared keys survive until their final reference clears. Failed acquisition rolls back only new resources. Removal events remain queued for the simulator tracking the vehicle. Reset clears pending mapping retries, missing zone definitions reject acquisition, and junction owner metadata is reclaimed when no reservation or lock references it.

### 验证 / Validation

下列回归直接对应上述行为，均随 `check` / `build` 执行：

| 检查入口 | 可追溯的用例与断言 |
|---|---|
| `routeReviewRegression` — [RouteReviewRegression](src/test/java/org/mtrbr/server/RouteReviewRegression.java)，15 项 | `terminalInvalidation / terminalTick`：5 种终态遇到失效与旧拓扑；`terminalRevoke / terminalApprove / terminalOverride / prematureCommands / staleApproval / manualApproval`：命令拒绝不修改授权、合法批准实际取得锁闭；`ownerRelease / ownerTransfer / ownerSweep / ownerFailure`：所有者清理；`auditBound / auditSnapshots`：写入 1024 条后仅保留第 768–1023 条、复用及冻结快照。 |
| `interlockingRegression` — [RetainedProtectionRegression](src/test/java/org/mtrbr/server/RetainedProtectionRegression.java)，7 项 | 整个 Block 占用保护、共享资源最后引用释放、active 授权保护、交接后清理、错误所有者不能解锁、确认移除释放。 |
| `interlockingRegression` — [InterlockingRegression](src/test/java/org/mtrbr/server/InterlockingRegression.java) | 4 项折返交接计时；6 项申请/回滚（含缺失 zone 和保留旧 zone 锁）；512 次并发分配覆盖 64 辆车，校验短码唯一性、反查与释放。 |
| `turnbackRegression` — [LifecycleRegression](src/test/java/org/mtrbr/server/LifecycleRegression.java)，15 项 | 短授权保护整个 Block、占用失效后的交接与清理、运行距离重置、共享 Block、距离与占用不一致、3 种移除入口 × 6 种请求状态、跨维度移除顺序、关闭后映射重试清空；另执行 13 项折返投影回归。 |

0.2.1 验证命令：`.\gradlew.bat build --offline --no-daemon`、`.\tools\check_regressions.ps1`、`git diff --check`，均通过。完整构建同时通过 22 项 LINE 路径、3 项 Web token、传感器、标牌及 26 项 Python 资源检查。构建日志：`build/release-0.2.1-build.log`；成品 `META-INF/mods.toml` 的版本字段已核对为 `0.2.1`。

The table maps each changed behavior to its regression source and fixture methods. The versioned full build, static checks and whitespace checks pass, including the existing LINE, Web, sensor, sign and resource checks. The built JAR metadata reports version `0.2.1`.

## 0.2.0 (2026-10-05)

Added a server-authoritative BR mode to the MTR `mtr:train_sensor`. The signal debug tool opens its settings; ordinary right-click interaction remains unavailable for this configuration screen.

Sensors now support `Approach Sections` and optional `Targeted Sections`. The target set must be a subset of the approach set. A sensor triggers only when the same train has an active BR authorization overlapping an approach section and, when configured, its current forward immutable path reaches a targeted section. Shared approach track followed by a branch to another route does not trigger the sensor.

Continuous output remains powered while the condition is true. Pulse output emits one configurable pulse per rising condition edge. Native MTR sensor behavior remains unchanged when BR mode is disabled.

The English Web UI lists sensor names, dimensions, coordinates, loaded state and output state. It supports direct sensor links, map selection, Ctrl multi-select, `BIND`, `CONFIRM`, `CANCEL`, `UNBIND` and `CLEAR ALL`. Web changes require an authenticated operator token and permission level 2.

Network protocol is `11`; update both client and server together. The release version is `0.2.0`.

- 新增灰边黑字的文字标牌：支持 1–3 个字母或数字，输入统一为大写，自动缩放并居中；牌面位置与杆子安装方式分别设置，物品图标显示 A。
- 信号机支架扩展为 (1)、(2)、两种双侧版本及 (1) 左/右护板。(1) 和护板版本支持 16 方位，其余支持四方位；修正斜向朝向并补齐护板旋转模型。
- 以手工调整的 `position_light_ground_dark.json` 同步所有地面/贴杆调车灯态及旋转模型，更新碰撞范围和预览；校验尺寸和外壳数量从母版读取。
- 标牌编辑数据包新增杆子安装字段，网络协议从 `9` 升至 `10`，客户端和服务器需一起更新。
- 旧 `signal_bracket` 注册名已改为 `signal_bracket_1`；当前未提供旧存档注册名自动迁移。

Added gray-bordered text signs with black, centered, auto-scaled lettering (1–3 alphanumeric characters, normalized to uppercase), independent plate/pole mounting controls and an A item icon. Expanded signal brackets to six variants, corrected diagonal orientation and supplied missing guard rotations. Synchronized all position-light variants, bounds and previews with the refined authored model.

The sign-edit packet now carries pole mounting; network protocol is `10`, requiring matching server and client builds. The old `signal_bracket` registry ID was replaced by `signal_bracket_1` without an automatic world migration.

## 0.1.2 更新版 / Refreshed Release (2026-09-09)

本次继续更新 `v0.1.2`，包含 9 月 8 日更新版的全部功能和以下调整；Minecraft、Forge、MTR 依赖及网络协议 `9` 不变。

This refresh updates `v0.1.2` with all features from the September 8 build and the changes below. Minecraft, Forge, MTR requirements and network protocol `9` are unchanged.

- LED 进路的 62 张数字、字母、短码和箭头贴图由 12×12 提升为 21×21，并在 Blockbench 中逐张细调，保留黑白像素风格及相近符号的区别；进路命名不变。
- 路径预览工具移至 `tools/preview_path_textures.py`，只读取 `path_*.png`，输出至 `build/previews/path-textures.png`；构建检查验证 21×21 尺寸。
- 清理旧 OBJ 转换、缩放、NBT 检查等一次性工具。
- 修复 GitHub Actions 环境缺少 `rg` 时静态回归脚本失败的问题，自动回退到 PowerShell `Select-String`。
- 调度台操作按钮使用英文，搜索栏支持中文和日文；箭头牌名称不再重复标注颜色。
- 调车越过入口节点后可立即申请下一个闭塞；连续调车逐段申请，越过普通主信号节点后恢复普通授权窗口。提前获得下一信号开放显示即可不停车通过，保留占用、锁闭及灯色发布检查。
- 信号计算周期调整为 10 tick，变化时同步、20 tick 兜底；调度和 Web 全量更新保持 20 tick。
- 调度列表增加搜索、状态筛选、表头排序、长字段省略和悬浮完整显示；调度操作增加服务端私有执行结果。
- 资源检查改用仓库内 MTR JAR，并接入 Gradle 和 GitHub Actions；更新统一预览目录说明。

Shunt requests roll forward by one Block after each passed entry, without requiring a stop at the following signal when clearance is already displayed. Signals refresh every 10 ticks. Dispatcher filtering, sorting, clipped fields and private execution results are added. Portable resource checks now run in Gradle and CI.

All 62 LED path textures have been individually refined in Blockbench at 21×21. The preview tool now lives in `tools/` and writes only to `build/previews/`; obsolete one-off tools are removed. CI static checks work without ripgrep. Dispatcher action labels use English, search accepts Chinese/Japanese, and arrow names omit redundant colours.

## 0.1.2 更新版 / Refreshed Release (2026-09-08)

此更新替换原有 `v0.1.2` release。Minecraft 1.20.1、Forge 47.4.18、MTR Forge 4.0.3；网络协议为 `9`，服务器与客户端均需替换为本次 JAR，不能与较早的同版本号 JAR 混用。

This build replaces the existing `v0.1.2` release. Requires Minecraft 1.20.1, Forge 47.4.18, and MTR Forge 4.0.3. Network protocol is `9`; update both server and clients, even when already using an earlier 0.1.2 JAR.

### 新增与调整 / Added and Updated

- 英式装饰限速牌：红色 PSR 圆牌、黄色 AWI 三角牌，均有单行/双行；两种颜色各有左/双向/右箭头牌。采用 Alte DIN 1451 Mittelschrift Regular 字体和平滑轮廓，圆牌直径 12/16 方块；仅作装饰。
- 限速牌字体按 SIL OFL 1.1 分发，随包附完整许可证和来源说明；字形图集、数字/字母图案及预览均已重新生成，箭头保持不变。
- 限速牌可编辑较低/较高限速或车型短码；双数字显示分隔线，车型/数字不显示分隔线。AWI 均使用三角背板，单箭头位置与双向箭头对应一半一致。
- 限速牌支持置中/触底/触顶，箭头支持触底/触顶；相邻组合在方块边界贴合。所有触顶牌使用全高 MTR 杆，其余杆不超出牌顶；保留 16 方位旋转。
- Current 布局的红灯型/黄灯型调车信号，支持落地/贴杆、独立 `shunt=` 进路及 `route=... || path=... || shunt=...` 组合绑定。包含近期调整的 LED 与调车模型资源。
- 调度面板两排按钮随窗口居中；新增生成 token 后自动打开 WebUI、个人 token 列表、按具体 token 销毁的入口。
- OP 可用 `web_token list_all` 私下查看各玩家有效 token，用 `web_token disable/enable <player>` 持久化控制生成权限。禁用只阻止新 token，不撤销已有 token；OP 权限要求保持不变。

Added decorative PSR/AWI signs with Alte DIN 1451 Mittelschrift Regular lettering, editable text, smooth arrows, matching mounts and MTR poles; current-layout position light signals and compound shunt bindings; centered dispatcher controls and private token management, including persistent per-player generation restrictions. The font is distributed under SIL OFL 1.1 with the complete licence and attribution; lettering assets have been regenerated without changing arrows.

### 修复 / Fixed

- 拆除最后一个调车信号后恢复普通主信号和授权判断，清理已加载区块内已删除设备的残留绑定；未加载区块不被误判为已删除。
- 调车双白开放且已有有效授权时，可不停车通过主信号；刷新 MTR 缓存停车点，但仍只开放第一段闭塞，后续控制点仍需获得相应授权和开放显示。
- 限速牌描边、文字间距、箭头圆角、杆子延续和包含杆厚度的选择范围调整。
- 保留此前 0.1.2 的原生折返/占用资源交接、LINE 路径方向和连续性修复，以及三进路/六方向指示器支持。

Removing position lights no longer leaves shunt-only restrictions. Authorized trains can pass a cleared shunt entry without an extra stop while retaining the one-Block limit. Sign outlines, text spacing, arrow corners and pole bounds are corrected. Earlier 0.1.2 turnback, occupied-resource handover, LINE validation and indicator improvements remain included.

### 验证 / Validation

完整 `gradlew build --no-daemon` 包含路径、信号、调度生命周期、限速牌文本/安装和 token 权限/生命周期回归。资源另由 `tools/check_speed_signs.py` 检查。自动检查不代替实际客户端窗口缩放、浏览器打开和行车场景验证。

The full build includes path, signal, dispatch lifecycle, speed-sign text/mounting, and token permission/lifecycle regressions. Speed-sign assets are checked separately with `tools/check_speed_signs.py`. Automated checks do not replace in-game layout, browser-opening, or driving validation.
