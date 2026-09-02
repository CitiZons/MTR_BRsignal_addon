# MTR_BRsignal_addon

Copyright (c) 2026 CitiZons. Licensed under the [MIT License](LICENSE).

Version: `0.1.1`

## 近期进展

- Web 调度界面采用 Terminus 风格，显示实时 Section 占用和锁闭、站台、信号机、车辆短码、请求抽屉、在线玩家权限状态，以及浏览器内的调度控制。
- Web 会话使用 `/mtrbr web_token generate`、`list` 与 `revocation` 管理；每个 token 仅绑定一台设备，泄漏或 OP 离线后永久失效，每个 OP 最多持有五个 token。专用服务器使用 `web_public_host` 与 MTR Web server 实际端口生成访问地址。
- 信号机名称可在 Web UI 中查看和修改；信号机图标可在浏览器中调整显示位置，不会改变游戏内的绑定关系。
- Depot 路径编辑器支持连续重复节点归一化、真实轨道端点显示坐标、候选节点预检、整条路径的方向约束求解，以及详细的无效路径诊断。现有 MTR Depot Path 仍是物理路径来源，编辑器不会绕过调度安全逻辑。
- Web 操作会写入既有 MTRBR debug 日志，包括路径预检/保存、调度操作、信号命名、操作者与失败上下文；不会记录 token 或 device ID。

## 文档

- [迁移计划.md](迁移计划.md)
- [设计大纲.md](设计大纲.md)
- [railway_block_design.md](railway_block_design.md)
- [模型.md](模型.md)

## 当前状态

信号和闭塞系统已基本完成。服务端的 SignalFace、RouteProjection、Section、Block、JunctionMovement、Request、Authorization 和 Movement Gate 主链路已经贯通，折返场景的路径 occurrence、授权前缀和资源释放也已纳入统一生命周期。

当前阶段以回归验证和运行观察为主。调度面板的 Approve/Revoke 只改变调度选择，不绕过 Section、Block 或 JunctionMovement 的安全检查。

## 简介

Minecraft Transit Railway（MTR）的英式闭塞信号扩展，Forge 1.20.1。

不改 MTR 本体源码或 jar，只通过 Mixin 覆盖信号判定、车辆停车和调度相关行为。

## 环境

- Minecraft 1.20.1
- Forge 47.3.0+
- MTR Forge 4.0.3

## 已实现功能

- British Railway 灯序：绿 → 双黄 → 单黄 → 红。
- 英式进路指示器。
- 服务端 `SectionState`、`RouteRequest`、`Authorization`、`MovementGate`。
- `RouteProjection` 从当前 immutablePath occurrence 确定 boundary、traversals、Sections、JunctionMovement 和稳定 Block definition；SavedBlock occurrence 映射不再决定当前授权 boundary。
- `Request` 保持列车完整 `immutablePath` 身份；`Authorization` 是同一 Request 的可恢复、动态扩展前缀，上限为 `min(下一停站, 下一折返边界, 后五个信号距离)`。
- 授权与 Activity 使用最后有效前缀恢复；刷新失败不会无保护地将运行中 Request 变成永久的无授权状态。入口面跨 tick 的 `0.05` 格容差避免车头刚越过 Block 边界时错误得到 `no next block`。
- 每个 Block occurrence 保存建立授权时的完整 Section/JunctionMovement 资源快照；所有释放路径使用该快照，不在释放阶段重新切片 traversal。
- `DENIED`、`REVOKED`、`CANCELED` Request 本身不构成资源持有理由；Authorization 和 pending physical occurrence 共同决定资源级 stale 清理。车辆仍实际占用的资源会保留到车尾清空。
- 闭塞身份使用持久化 `A->B` 和 canonical Block occurrence；Section、Block、Junction 均在车尾通过对应 Block 末端后释放，未清空资源保留锁闭。
- 折返站前 4 格交还 MTR 原生终到计算，以触发原生开门、终到和反向；Authorization 仍只负责安全前缀。
- 色灯式 / LED 式进路指示器。色灯 route 灯按模型元素边界绘制六面、使用全亮 `QueuedRenderLayer.LIGHT`，并把已解析的 route 灯几何缓存到客户端 `config/mtr_brsignal_addon/route-light-cache.json`。
- 色灯进路指示器接入与 MTR 信号相同的远距渲染通道：近处原版方块实体渲染，48 至 320 格的已加载区块由关卡渲染阶段接管。
- 调度面板末列为 `Occ/Auth/Lock`，按 Block 访问实例而非 Rail/Section 数量统计。
- 信号机调试工具：命名、节点绑定、方向切换、进路绑定、指示器绑定。
- 进路工具：绑定 `route=X` 或 `path=Y`，手持显示连线和乘车 HUD。
- 调度工具：右键空气或调度台打开调度面板；乘车时顶部显示 Vehicle ID、Route、Destination。
- 调度台方块：右键打开调度面板。
- 内嵌 Web 调度图：显示轨道 Section、站台、信号机、车辆短码、占用/锁闭状态及在线玩家权限状态；使用随 mod 打包的 Terminus 字体，不依赖本机字体。
- Web 请求抽屉：按短码自然顺序显示 `Code`、`State`、`Route`、`Next` 和 `Dest`；点击条目或地图短码会将地图居中到车辆并高亮其请求区段。重复点击可取消选中。
- Web 调度授权：OP 通过短期 token 获得 Approve、Revoke 和 Override 操作；无 token 访问始终只读。请求状态颜色与游戏内调度面板一致。

## 调度命令

```text
/mtrbr requests                                    # 列出当前所有进路请求及其授权、锁定状态
/mtrbr approve <vehicle_code>                      # 手动批准指定车辆的当前进路请求
/mtrbr revoke_pending <vehicle_code>               # 撤销指定车辆尚未生效的待处理授权
/mtrbr manual_override <vehicle_code> <true|false> # 开启或关闭指定车辆的人工调度覆盖
/mtrbr priority <vehicle_code> <value>             # 设置指定车辆的调度优先级数值
/mtrbr audit                                       # 输出当前进路、闭塞与资源状态的诊断审计
/mtrbr protection regenerate                       # 按当前信号拓扑重建 SignalFace 到 Block 的保护定义
/mtrbr web_token generate                          # 生成一个带调度 token 的完整 Web URL（每个 OP 最多 5 个）
/mtrbr web_token list                              # 列出自己的 token，按 1-5 编号
/mtrbr web_token revocation <number>               # 注销指定编号的 token，剩余 token 自动重新编号
```

## Web 调度

启用 MTR Web server 后，在游戏内以 OP 身份执行 `/mtrbr web_token generate`。命令只会向执行者本人发送一个可点击的完整地址。单人存档使用：

```text
http://localhost:<port>/mtrbr/?token=<token>
```

专用服务器必须在服务器配置中设置 `web_public_host`，例如 `chousile.cn`；生成的地址会使用该主机名和 MTR Web server 的实际端口。配置为空时，专服拒绝生成 URL，不会猜测绑定地址或公网 IP。

每个 token 绑定一个浏览器设备。同一 token 被不同设备访问时，token 永久标记为 `LEAKED`；对应 OP 离线时，所有该 OP 的有效 token 永久标记为 `PLAYER_OFFLINE`。失效后的 Web UI 始终只读，服务器也会拒绝所有调度和信号命名 API。失效 token 仍占用 OP 的五个 token 名额，直到执行 `/mtrbr web_token revocation <number>`。

首次部署或调整信号拓扑后执行 `/mtrbr protection regenerate`，重新生成规范的 `SignalFace -> A->B -> railIds` Block definition；无法确定当前 occurrence 的稳定 definition 时授权明确失败为 `BLOCK_DEFINITION_MISSING`，不会借用旧 terminal、其他 occurrence 或其他 path fingerprint。运行诊断可在 `logs/mtrbr-debug.log` 中查看 `MTRBR-ROUTE-PROJECTION`、`MTRBR-AUTH-*`、`MTRBR-RESOURCE-RELEASE`、`MTR_TURNBACK_*` 和 `MTRBR-DEPARTURE-GUARD`。

## 构建与部署

1. 将 `MTR-forge-4.0.3+1.20.1.jar` 放入 `libs/`。
2. 执行：

```sh
gradlew.bat build
```
产物在`build/libs`文件夹下。

## 静态检查

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\check_regressions.ps1
```

Java 编译检查：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-8.0.482.8-hotspot"
.\gradlew.bat compileJava --no-daemon
```

当前源码已通过 `compileJava`；构建输出中的弃用 API 提示不属于信号、闭塞或资源生命周期逻辑。

## 许可证与第三方声明

本项目源代码采用 [MIT License](LICENSE)。随 JAR 分发的 Terminus Regular
字体采用 SIL Open Font License 1.1；其版权声明和完整许可证文本，以及其他
第三方声明，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。Minecraft、
Forge 和 MTR 是其各自权利人的产品或项目，本项目不授予其商标或许可证权利。
