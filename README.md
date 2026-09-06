# MTR_BRsignal_addon

Copyright (c) 2026 CitiZons. Licensed under the [MIT License](LICENSE).

Version: `0.1.2`

## 简介 / Introduction

这是一个适用于 Minecraft 1.20.1 Forge 和 MTR 4.0.3 的英式铁路信号扩展 mod。它提供服务端权威的闭塞、进路授权、调度和车辆停车逻辑，并加入色灯/LED 进路指示器、复示信号机和 Web 调度界面。

This is a British railway signalling addon for Minecraft 1.20.1 Forge and MTR 4.0.3. It provides server-authoritative blocks, route authorization, dispatching, and vehicle stopping, together with colour-light/LED route indicators, repeater signals, and a web dispatch view.

项目不修改 MTR 本体源码或原始 JAR，只在 addon 内通过 Mixin 和扩展逻辑接入 MTR。

The project does not modify the MTR source code or original JAR. It integrates with MTR through addon-side mixins and extension logic.

## 环境与安装 / Requirements and Installation

需要 Minecraft 1.20.1、Forge 47.4.18、MTR Forge 4.0.3。将构建产物放入客户端或服务器的 `mods/` 文件夹，并确保 MTR 依赖已安装。

You need Minecraft 1.20.1, Forge 47.4.18, and MTR Forge 4.0.3. Put the built JAR in the client or server `mods/` folder and make sure the MTR dependency is installed.

从源码构建：

To build from source:

```powershell
.\gradlew.bat build --no-daemon
```

产物位于 `build/libs/mtr_brsignal_addon-0.1.2.jar`。

The output is `build/libs/mtr_brsignal_addon-0.1.2.jar`.

## 快速使用 / Quick Start

使用信号机调试工具右键信号机可打开调试面板；Shift+右键信号机后再 Shift+右键轨道节点可绑定作用节点。Shift+右键进路指示器或复示信号机，再 Shift+右键信号机，可完成显示设备绑定。右键显示设备可查看状态、解绑或切换支撑/下挂。

Right-click a signal with the signal debug tool to open its debug screen. Shift-right-click a signal and then a rail node to bind the controlled node. Shift-right-click a route indicator or repeater signal and then the main signal to bind the display. Right-click a display device to inspect, unbind, or change its mounting.

使用进路工具右键信号机选中目标，再 Shift+右键轨道节点创建进路绑定。色灯式使用 `route=1|2|4|5`；LED 式使用 `path=...`。手持相关工具时会显示诊断连线。

Select a signal with the route tool, then Shift-right-click a rail node to create a route binding. Colour-light indicators use `route=1|2|4|5`; LED indicators use `path=...`. Diagnostic lines are shown while the relevant tool is held.

调车信号使用独立的 `shunt=名称`（如 `shunt=yard_1`）。先用调试工具将调车信号绑定到主信号，再用进路工具将主信号绑定到目标节点并输入该名称。调车获准时主信号保持红灯，调车信号显示斜向双白，只授权到下一同向信号或线路终端。调试界面可切换落地／贴杆；贴杆式包含与 MTR 信号柱接续的柱段，平时熄灭。

Position light signals use the separate `shunt=name` format, for example `shunt=yard_1`. Bind the position light to a main signal with the debug tool, then bind that main signal to the destination node with the route tool and enter the shunt name. Shunt clearance keeps the main signal red and shows two diagonal white lights, authorizing only the first Block up to the next same-direction signal or terminal. The debug screen switches between ground and pole mounting; the pole version includes a segment that joins MTR signal poles and is normally unlit.

同一节点可用 `||` 组合不同类型的绑定，例如 `path=1 || shunt=yard_1`，或 `route=2 || path=1 || shunt=yard_1`；每种类型最多一项。输入界面第一行列出 route/path 可用选项，第二行列出 shunt 选项。落地式宽、高均为 12/16 方块，左右居中，保留底座和灰色短杆；基础几何按 1/32 网格重排，背板高 10/16，外壳贴图保持每方块 32 像素。

Combine different binding types for the same node with `||`, for example `path=1 || shunt=yard_1` or `route=2 || path=1 || shunt=yard_1`, with at most one value per type. The input screen lists route/path options on the first line and shunt options on the second. Ground models are centered, 12/16 blocks wide and tall, with a base and grey post. Geometry uses the 1/32 grid with a 10/16 tall backplate; housing textures retain 32 pixels per block.

右键调度台或使用调度工具可打开调度面板。OP 可以执行 `/mtrbr web_token generate` 获取 Web 调度地址。首次部署或调整信号拓扑后，建议执行 `/mtrbr protection regenerate`。

Right-click the dispatcher console or use the dispatcher tool to open the dispatch screen. An operator can run `/mtrbr web_token generate` to obtain a web dispatch URL. After first deployment or a signal topology change, run `/mtrbr protection regenerate`.

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
/mtrbr web_token revocation <number>
```

## 文档 / Documentation

完整的架构、闭塞语义、资源制作、Web 调度、工程结构、迁移历史和后续计划见 [内容说明.md](内容说明.md)。更底层的闭塞设计记录见 [railway_block_design.md](railway_block_design.md)，第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

For the complete architecture, block semantics, resource workflow, web dispatch, project structure, migration history, and future plans, see [内容说明.md](内容说明.md). Lower-level block design notes are in [railway_block_design.md](railway_block_design.md), and third-party licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 构建检查 / Build Checks

完整构建会运行 Java 编译、资源处理以及 LINE、折返、指示器、复示信号机和调度生命周期回归测试。

A full build runs Java compilation, resource processing, and regression checks for LINE paths, turnbacks, indicators, repeater signals, and dispatch lifecycles.

```powershell
.\gradlew.bat compileJava --no-daemon
.\gradlew.bat build --no-daemon
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check_regressions.ps1
python tools/check_triple_indicators.py
python tools/check_six_route_indicators.py
python tools/check_signal_mounts.py
```

## 许可证与鸣谢 / Licence and Acknowledgements

源代码采用 [MIT License](LICENSE)。Terminus Regular 字体和其他第三方组件的许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。感谢 ChatGPT 5.6、ChatGPT 6 和 Codex 在设计、实现、测试和文档整理中的协作支持。

The source code is released under the [MIT License](LICENSE). Licences for Terminus Regular and other third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Thanks to ChatGPT 5.6, ChatGPT 6, and Codex for their assistance with design, implementation, testing, and documentation.
