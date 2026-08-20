# MTR_BRsignal_addon

## ⚠️ 当前状态

本项目仍处于**开发测试阶段**，不适用于生产环境。当前版本已完成服务端闭塞、Request/Authorization、Movement Gate、调度面板和调度工具的主要链路，但仍存在以下已知问题：

- 西行方向的部分信号机可能不响应列车路径，表现为一直红灯。
- 尽头式站台前的进站信号可能长时间不开放，列车停在站外。
- 调度面板的 Approve/Revoke 在部分车辆状态下可能仍需人工确认或补充日志排查。
- 模型和贴图仍有部分资源未最终完成。

## 简介

Minecraft Transit Railway（MTR）的英式闭塞信号扩展，Forge 1.20.1。

不改 MTR 本体源码或 jar，只通过 Mixin 覆盖信号判定、车辆停车和调度相关行为。

## 环境

- Minecraft 1.20.1
- Forge 47.3.0+
- MTR Forge 4.0.3

## 已实现功能

- BR 灯序：绿 → 双黄 → 单黄 → 红。
- 服务端 `SectionState`、`RouteRequest`、`Authorization`、`MovementGate`。
- `Request` 基于列车完整 `immutablePath` 申请，窗口长度限制为 `min(下一运营停车点距离, 前方约 4 个信号控制边界距离)`。
- `Authorization` 是 Request 当前可开放前缀，随列车推进动态扩展，遇占用 Section 前截断。
- 闭塞区间按相邻 SignalFace 绑定节点切分，不再按单条 Rail 判断。
- 色灯式 / LED 式进路指示器。
- 信号机调试工具：命名、节点绑定、方向切换、进路绑定、指示器绑定。
- 进路工具：绑定 `route=X` 或 `path=Y`，手持显示连线和乘车 HUD。
- 调度工具：右键空气或调度台打开调度面板；乘车时顶部显示 Vehicle ID、Route、Destination。
- 调度台方块：黑紫格子材质，右键打开调度面板。

## 调度命令

```text
/mtrbr requests
/mtrbr approve <vehicle_id>
/mtrbr revoke_pending <vehicle_id>
/mtrbr manual_override <vehicle_id> <true|false>
/mtrbr priority <vehicle_id> <value>
/mtrbr audit
```

## 构建与部署

1. 将 `MTR-forge-4.0.3+1.20.1.jar` 放入 `libs/`。
2. 执行：

```sh
gradlew.bat build
```

3. 部署脚本：

```powershell
.\build_deploy_alpha4.ps1
```

产物复制到 `C10.alpha.4.client\.minecraft\mods\mtr_brsignal_addon-0.1.0.jar`。

## 静态检查

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check_regressions.ps1
```

## 文档

- [迁移计划.md](迁移计划.md)
- [设计大纲.md](设计大纲.md)
- [railway_block_design.md](railway_block_design.md)
- [模型.md](模型.md)
