# MTR_BRsignal_addon

## ⚠️ ***当前版本仍存在较多已知问题，尤其是模型贴图和渲染部分，且仍有部分贴图和模型未开发完成。该版本仅用于开发测试，暂不建议在生产环境使用。***

Minecraft Transit Railway（MTR）的**英式闭塞信号扩展**补充 Mod（Forge 1.20.1）。

不改动 MTR 原文件：用 Mixin 逐步接入服务端闭塞与车辆执行层，加入 BR 风格的
**绿 → 双黄 → 单黄 → 红** 闭塞链，并新增色灯式 / LED 式进路指示器与配套工具。

## 环境要求

- Minecraft 1.20.1
- Forge 47.3.0+
- MTR（Forge 版）4.0.3

## 功能

- **BR 闭塞链**：最终由服务端完整 RouteRequest、SectionState 和 Authorization 派生；
  当前显示实验仍按前方信号链计算，列车头部进入 Section 即占用、尾部离开才释放。
- **色灯式进路指示器**（`indicator_1`）：运行时直接解析
  `indicator_1_NULL.bbmodel` 渲染全部几何，route 图层按进路状态显隐。
- **LED 式进路指示器**（`led_indicator`）：显示 `path=0..20 / A..Z / UF,US,DF,DS`。
- **信号机调试工具**：右键信号机打开调试面板（命名 / 灯状态 / 节点 / 进路 / 指示器解绑）；
  Shift+右键完成节点与指示器绑定。
- **进路工具**：绑定进路并输入 `route=X` / `path=Y`；手持显示连线与标签；
  乘车上显示下一信号机进路。
- 信号机命名、工具 Shift 悬停说明、半透明连线可视化。
- 人工驾驶 Override 规划：人工驾驶列车可经服务端授权越过红灯并进入 occupied Section；不改变自动车辆 Aspect。

## 构建

1. 将 `MTR-forge-4.0.3+1.20.1.jar` 放入 `libs/`（仓库已忽略该依赖 jar）。
2. 执行：

```sh
gradlew.bat build
```

产物：`build/libs/mtr_brsignal_addon-0.1.0.jar`。

## 使用速览

| 工具 | 操作 | 作用 |
|---|---|---|
| 信号机调试工具 | 右键信号机 | 打开调试面板 |
| 信号机调试工具 | Shift+右键信号机 → Shift+右键轨道节点 | 绑定信号机到节点 |
| 信号机调试工具 | Shift+右键指示器 → Shift+右键信号机 | 绑定指示器到信号机 |
| 信号机调试工具 | 右键进路指示器 | 查看信息 / 解绑 |
| 进路工具 | 右键信号机 → Shift+右键节点 | 绑定进路并输入内容 |

进路内容格式：`route=1|2|4|5` 或 `path=0..20 / 大写字母 / UF,US,DF,DS`（`UP/DW` 已弃用）。
`path=NULL` / `route=NULL` 表示不显示。绑定信号机为**红灯**时进路显示立即熄灭。

## 文档

- [设计大纲.md](设计大纲.md) —— 机制、数据、网络、渲染与待办。
- [模型.md](模型.md) —— 模型 / 贴图清单与资源约定。

## 架构原则

```text
Vehicle immutablePath -> RouteRequest -> SectionCheck -> Dispatcher
-> Authorization -> Signal Aspect -> Movement Gate
```

- Request 是一列车申请一条完整进路，不是单个信号请求。
- SectionState 是服务端权威状态库；客户端只显示 Aspect、Authorization 和调度信息。
- `Dispatcher Override` 不绕过安全检查；`Manual Driving Override` 只对人工驾驶车辆生效，可进入 occupied Section。

## 说明

- 所有 MTR 信号机（含羊毛连线信号）都会参与 BR 闭塞链，其作用节点即闭塞区段端点；
  无信号机的普通节点不是区段端点。
- 当前仍主要为显示层实现；让自动列车按服务端闭塞真实停车/减速的 Movement Gate 执行层尚未完成。
- 网络协议版本：4（客户端与服务端需使用同一版本 jar）。
