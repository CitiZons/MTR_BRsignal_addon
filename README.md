# MTR_BRsignal_addon

Copyright (c) 2026 CitiZons. Licensed under the [MIT License](LICENSE).

Version: `0.1.2`

## 简介 / Introduction

这是一个适用于 Minecraft 1.20.1 Forge 和 MTR 4.0.3 的英式铁路信号扩展 mod。它提供服务端权威的闭塞、进路授权、调度和车辆停车逻辑，并加入色灯/LED 进路指示器、复示信号机、调车信号、装饰性限速牌和 Web 调度界面。

This is a British railway signalling addon for Minecraft 1.20.1 Forge and MTR 4.0.3. It provides server-authoritative blocks, route authorization, dispatching, and vehicle stopping, together with colour-light/LED route indicators, repeater and position light signals, decorative speed signs, and a web dispatch view.

项目不修改 MTR 本体源码或原始 JAR，只在 addon 内通过 Mixin 和扩展逻辑接入 MTR。

The project does not modify the MTR source code or original JAR. It integrates with MTR through addon-side mixins and extension logic.

## 环境与安装 / Requirements and Installation

需要 Minecraft 1.20.1、Forge 47.4.18、MTR Forge 4.0.3。将构建产物放入客户端或服务器的 `mods/` 文件夹，并确保 MTR 依赖已安装。

You need Minecraft 1.20.1, Forge 47.4.18, and MTR Forge 4.0.3. Put the built JAR in the client or server `mods/` folder and make sure the MTR dependency is installed.

从源码构建需要 JDK 17；必要时先将 `JAVA_HOME` 指向 JDK 17：

To build from source, use JDK 17 and set `JAVA_HOME` to its installation directory if necessary:

```powershell
python -m pip install -r tools/requirements.txt
.\gradlew.bat build --no-daemon
```

产物位于 `build/libs/mtr_brsignal_addon-0.1.2.jar`。

The output is `build/libs/mtr_brsignal_addon-0.1.2.jar`.

## 快速使用 / Quick Start

使用信号机调试工具右键信号机可打开调试面板；Shift+右键信号机后再 Shift+右键轨道节点可绑定作用节点。Shift+右键进路指示器或复示信号机，再 Shift+右键信号机，可完成显示设备绑定。右键显示设备可查看状态、解绑或切换支撑/下挂。

Right-click a signal with the signal debug tool to open its debug screen. Shift-right-click a signal and then a rail node to bind the controlled node. Shift-right-click a route indicator or repeater signal and then the main signal to bind the display. Right-click a display device to inspect, unbind, or change its mounting.

使用进路工具右键信号机选中目标，再 Shift+右键轨道节点创建进路绑定。色灯式使用 `route=1` 至 `route=6`；LED 式使用 `path=...`。手持相关工具时会显示诊断连线。

Select a signal with the route tool, then Shift-right-click a rail node to create a route binding. Colour-light indicators use `route=1` through `route=6`; LED indicators use `path=...`. Diagnostic lines are shown while the relevant tool is held.

调车信号使用独立的 `shunt=名称`（如 `shunt=yard_1`）。先用调试工具将调车信号绑定到主信号，再用进路工具将主信号绑定到目标节点并输入该名称。调车获准时主信号保持红灯，调车信号显示斜向双白，只授权到下一同向信号或线路终端。调试界面可切换落地／贴杆；贴杆式包含与 MTR 信号柱接续的柱段，平时熄灭。

Position light signals use the separate `shunt=name` format, for example `shunt=yard_1`. Bind the position light to a main signal with the debug tool, then bind that main signal to the destination node with the route tool and enter the shunt name. Shunt clearance keeps the main signal red and shows two diagonal white lights, authorizing only the first Block up to the next same-direction signal or terminal. The debug screen switches between ground and pole mounting; the pole version includes a segment that joins MTR signal poles and is normally unlit.

同一节点可用 `||` 组合不同类型的绑定，例如 `path=1 || shunt=yard_1`，或 `route=2 || path=1 || shunt=yard_1`；每种类型最多一项。输入界面第一行列出 route/path 可用选项，第二行列出 shunt 选项。落地式宽、高均为 12/16 方块，左右居中，保留底座和灰色短杆；基础几何按 1/32 网格重排，背板高 10/16，外壳贴图保持每方块 32 像素。

Combine different binding types for the same node with `||`, for example `path=1 || shunt=yard_1` or `route=2 || path=1 || shunt=yard_1`, with at most one value per type. The input screen lists route/path options on the first line and shunt options on the second. Ground models are centered, 12/16 blocks wide and tall, with a base and grey post. Geometry uses the 1/32 grid with a 10/16 tall backplate; housing textures retain 32 pixels per block.

调车双白开放且本车已有有效授权时，可不停车通过其主信号，主信号仍保持红灯；授权仍只到下一控制点。拆除最后一个绑定的调车信号后，主信号恢复普通进路判断，保留的 `shunt=` 文本不会单独触发调车模式。旧停车点缓存和已删除设备的残留绑定会被清理。

With white shunt clearance and a valid authorization, a train can pass the associated red main signal without stopping, still limited to the next control point. Removing the last bound position light restores normal route evaluation; retained `shunt=` text alone does not enable shunting. Stale stopping-point caches and bindings to deleted devices are cleared.

越过调车入口节点后即可申请下一段，无需先在下一灯停车。在越过无调车设备或由普通主信号放行的节点前，每次只增加一个前方闭塞；连续调车信号逐段申请。下一信号提前获得授权并发布开放显示时可连续通过，未开放则停在其节点。信号每 10 tick 计算一次（20 TPS 时约 0.5 秒），显示变化即同步，每 20 tick 兜底同步。

After passing a shunt entry node, the train may immediately request the next Block. One additional forward Block is permitted until it passes a signal operating under ordinary main-signal authority. Consecutive shunts advance this window one node at a time. A published clearance permits continuous travel through the next signal; otherwise its node remains the stopping boundary. Aspects are calculated every 10 ticks, synchronized when changed, with a 20-tick fallback.

## 限速牌 / Decorative Speed Signs

提供红色圆形 PSR 永久限速牌、黄色三角形 AWI 限速预告牌，各有单行和双行版本；红色、黄色箭头牌各有左、双向、右三种。圆牌直径为 12/16 方块，使用 Alte DIN 1451 Mittelschrift Regular 字体（SIL OFL 1.1）、平滑轮廓和内置 MTR 信号杆材质。所有限速牌仅作装饰，不改变列车速度、进路或闭塞。

Red circular PSR signs and yellow triangular AWI signs each have single- and double-line variants, plus red/yellow left, both-way, and right arrow plates. Circular signs are 12/16 blocks in diameter, with Alte DIN 1451 Mittelschrift Regular lettering (SIL OFL 1.1), smooth contours, and a bundled MTR pole texture. These signs are decorative only and do not control trains.

用信号机调试工具右键限速牌编辑。单行填写 1–999；双行填写“较低限速”和“较高限速”，前者必须小于后者并显示黑色分隔线。较低限速栏也可填写至多四位、以字母开头的车型短码（如 `HST`、`DMU`、`S7`），此时不显示分隔线；AWI 始终使用三角背板。

Right-click with the signal debug tool to edit. Single-line signs accept 1–999. Double-line numeric signs require the lower speed above the higher speed and show a black divider. The upper field may instead contain a train-class code of up to four characters starting with a letter, such as `HST`, `DMU`, or `S7`; these omit the divider. AWI backplates remain triangular in both cases.

限速牌可选择置中、触底、触顶；箭头牌仅触底/触顶，调试工具右键立即切换。限速牌下接箭头时使用“限速牌触底 + 箭头触顶”，上接箭头时使用“限速牌触顶 + 箭头触底”。相邻组合会同步安装位置和朝向，保持原有牌形并在方块边界贴合。所有触顶牌的杆子均为全高，其余从方块底部延伸到牌后并在牌顶以内结束。支持 16 方位、每档 22.5° 旋转。

Speed signs support center, bottom, and top mounting; arrow plates support bottom/top only, toggled immediately with the debug tool. Pair a bottom-mounted speed sign with a top-mounted arrow below it, or a top-mounted speed sign with a bottom-mounted arrow above it. Adjacent pairs align their mounting and rotation without changing plate shape. Top mounts have full-height poles; other poles start at the block floor and end behind the plate. All signs support 16 orientations at 22.5° intervals.

## 调度与 Token / Dispatch and Tokens

右键调度台或使用调度工具打开面板。底部两排按钮随窗口宽度居中；第二排提供“生成 Token”（成功后自动打开默认浏览器 WebUI）、“Token 列表”（个人聊天框）和“销毁 Token”（聊天框中选择具体 token）。直接执行 `/mtrbr web_token generate` 仍只返回可点击地址。

Open the console with the dispatcher block or tool. Both button rows stay centered as the window changes. The second row generates a token and opens the Web UI in your default browser, lists your tokens in private chat, or shows clickable token revocation entries. The plain `/mtrbr web_token generate` command still returns a clickable URL.

列表支持搜索编号/线路/车站、状态筛选、点击表头升降序排序。窄窗口中长字段显示省略，悬浮可查看完整内容。批准、撤销、一次越行由服务端执行后私下回复结果及拒绝原因。

The table supports text search, status filters and header sorting. Narrow columns truncate long fields and show complete values on hover. Approve, revoke and one-shot override actions receive private server execution results, including rejection reasons.

Token 和 Web 写操作保持 OP 权限要求。`list_all` 按玩家列出有效 token，仅执行命令的 OP 收到结果。`disable/enable` 只控制新 token 的生成，按 UUID 保存且重启后保留，不撤销已有 token。专用服务器需要先配置 `web_public_host` 并启用 MTR Web server。首次部署或调整信号拓扑后，建议执行 `/mtrbr protection regenerate`。

Tokens and Web write operations continue to require OP permissions. `list_all` groups valid tokens by player and replies only to the requesting operator. `disable/enable` controls new issuance, persists by UUID across restarts, and does not revoke existing tokens. Dedicated servers must configure `web_public_host` and enable the MTR Web server. Run `/mtrbr protection regenerate` after initial setup or signal topology changes.

本次 0.1.2 更新的网络协议为 `9`，客户端与服务器必须同时替换为本次 JAR；相同版本号的较早 JAR 不兼容。更新列表见 [CHANGELOG.md](CHANGELOG.md)。

This refreshed 0.1.2 uses network protocol `9`. Update both clients and servers to this build; older JARs with the same version number are incompatible. See [CHANGELOG.md](CHANGELOG.md).

## 常用命令 / Common Commands

以下命令用于查看请求、调度车辆、检查状态和管理 Web token。

The following commands inspect requests, dispatch vehicles, audit state, and manage web tokens.

```text
/mtrbr requests
/mtrbr approve <vehicle_code>
/mtrbr revoke_pending <vehicle_code>
/mtrbr manual_override <vehicle_code> <true|false>
/mtrbr priority <vehicle_code> <value>
/mtrbr audit
/mtrbr protection regenerate
/mtrbr web_token generate
/mtrbr web_token list
/mtrbr web_token revocation
/mtrbr web_token revocation <number>
/mtrbr web_token list_all
/mtrbr web_token disable <player>
/mtrbr web_token enable <player>
```

## 文档 / Documentation

完整的架构、闭塞语义、资源制作、Web 调度、工程结构、迁移历史和后续计划见 [内容说明.md](内容说明.md)。更底层的闭塞设计记录见 [railway_block_design.md](railway_block_design.md)，第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

For the complete architecture, block semantics, resource workflow, web dispatch, project structure, migration history, and future plans, see [内容说明.md](内容说明.md). Lower-level block design notes are in [railway_block_design.md](railway_block_design.md), and third-party licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 构建检查 / Build Checks

完整构建会运行 Java 编译、资源处理以及 LINE、折返、指示器、复示信号机、调度生命周期、限速牌文本/安装和 token 权限/生命周期回归测试。Python 资源检查需要 Pillow；调车模型预览还需要 NumPy。

A full build runs Java compilation, resource processing, and regression checks for LINE paths, turnbacks, indicators, repeater signals, dispatch lifecycles, speed-sign text/mounting, and token permissions/lifecycles. Python resource checks require Pillow; position-light previews also require NumPy.

`gradlew build` 同时运行只读资源检查；Python 可通过 `-PpythonExecutable=完整路径` 指定。材质对照直接读取仓库内 `libs/` 的 MTR JAR，不依赖旁边的 MTR 源码目录。GitHub Actions 在 push/PR 时执行同一构建和静态检查。

The build also runs read-only resource checks. Override Python with `-PpythonExecutable=...` when needed. Texture verification uses the bundled MTR dependency JAR. GitHub Actions runs the build and static checks on pushes and pull requests.

```powershell
.\gradlew.bat compileJava --no-daemon
.\gradlew.bat build --no-daemon
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check_regressions.ps1
python tools/check_triple_indicators.py
python tools/check_six_route_indicators.py
python tools/check_signal_mounts.py
python tools/check_speed_signs.py
```

所有预览统一输出到 `build/previews/`。以下命令只读取现有模型并生成进路指示器与调车信号预览，不重写模型资源；限速牌预览由 `tools/generate_speed_signs.py` 随限速牌资源一起生成。`gradlew clean` 会删除整个 `build/`，需要保留预览时直接运行 `gradlew build`。

All previews go to `build/previews/`. These commands render existing indicator and position-light models without rewriting resources. The speed-sign generator produces its preview alongside its resources. Use `gradlew build` without `clean` to retain existing previews.

```powershell
python tools/preview_indicators.py
python -c "import sys; sys.path.insert(0, 'tools'); from check_position_light_signals import preview; preview()"
```

## 许可证与鸣谢 / Licence and Acknowledgements

源代码采用 [MIT License](LICENSE)。Alte DIN 1451 Mittelschrift Regular 和 Terminus Regular 字体采用 SIL OFL 1.1；字体及 MTR 信号杆材质的来源与许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。感谢 ChatGPT 5.6、ChatGPT 6 和 Codex 在设计、实现、测试和文档整理中的协作支持。

The source code is released under the [MIT License](LICENSE). Alte DIN 1451 Mittelschrift Regular and Terminus Regular use SIL OFL 1.1. Sources and licence notices for these fonts and the MTR pole texture are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Thanks to ChatGPT 5.6, ChatGPT 6, and Codex for their assistance with design, implementation, testing, and documentation.
