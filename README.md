# MTR_BRsignal_addon

## ⚠️ 当前状态

本项目仍处于**开发测试阶段**，不适用于生产环境。当前版本已完成服务端闭塞、Request/Authorization、Movement Gate、调度面板、调度工具和进路指示器的主要链路，但仍存在以下已知问题：

- 个别原生 MTR 折返场景仍需继续验证：2026-08-24 测试中 `000C` 在关门后未完成折返。
- 个别授权窗口仍可能在特定节点停滞：2026-08-24 测试中 `000D` 停在 `341.9`，尚未修复。
- 调度面板的 Approve/Revoke 只改变调度选择，不应作为上述运行问题的绕过手段。

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
- `Request` 保持列车完整 `immutablePath` 身份；`Authorization` 是同一 Request 的可恢复、动态扩展前缀，上限为 `min(下一停站, 下一折返边界, 后五个信号距离)`。
- 授权与 Activity 使用最后有效前缀恢复；刷新失败不会无保护地将运行中 Request 变成永久的无授权状态。入口面跨 tick 的 `0.05` 格容差避免车头刚越过 Block 边界时错误得到 `no next block`。
- 闭塞身份使用持久化 `A->B` 和 canonical Block occurrence；Section、Block、Junction 均在车尾通过对应 Block 末端后释放，未清空资源保留锁闭。
- 折返站前 4 格交还 MTR 原生终到计算，以触发原生开门、终到和反向；Authorization 仍只负责安全前缀。
- 色灯式 / LED 式进路指示器。色灯 route 灯按模型元素边界绘制六面、使用全亮 `QueuedRenderLayer.LIGHT`，并把已解析的 route 灯几何缓存到客户端 `config/mtr_brsignal_addon/route-light-cache.json`。
- 色灯进路指示器接入与 MTR 信号相同的远距渲染通道：近处原版方块实体渲染，48 至 320 格的已加载区块由关卡渲染阶段接管。
- 调度面板末列为 `Occ/Auth/Lock`，按 Block 访问实例而非 Rail/Section 数量统计。
- 信号机调试工具：命名、节点绑定、方向切换、进路绑定、指示器绑定。
- 进路工具：绑定 `route=X` 或 `path=Y`，手持显示连线和乘车 HUD。
- 调度工具：右键空气或调度台打开调度面板；乘车时顶部显示 Vehicle ID、Route、Destination。
- 调度台方块：黑紫格子材质，右键打开调度面板。

## 调度命令

```text
/mtrbr requests
/mtrbr approve <vehicle_code>
/mtrbr revoke_pending <vehicle_code>
/mtrbr manual_override <vehicle_code> <true|false>
/mtrbr priority <vehicle_code> <value>
/mtrbr audit
/mtrbr protection regenerate
```

首次部署或调整信号拓扑后执行 `/mtrbr protection regenerate`，重新生成规范的 `SignalFace -> A->B -> railIds` 闭塞映射；无法确定下一同向信号的 face 保持无映射并按安全侧处理。运行诊断可在 `logs/mtrbr-debug.log` 中查看 `MTRBR-AUTH-*`、`MTRBR-RESOURCE-RELEASE`、`MTR_TURNBACK_*` 和 `MTRBR-DEPARTURE-GUARD`。

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
