# MTR BR Signal Addon

[English](README.en.md) | [简体中文](README.zh-CN.md)

**让你的铁路真正拥有信号系统，而不只是装饰。**

[MTR](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) BR Signal Addon 为 Minecraft Transit Railway 增加服务端权威的英式铁路信号系统：列车占用真实区间、申请完整进路、获得安全行车许可，并通过色灯信号、进路指示器和调车信号显示状态。

同时，模组还提供调度台、私有 Web UI、支持进路的红石感应器，以及一套适合完整铁路控制系统的标牌。

## 功能概览

| 功能 | 说明 |
|---|---|
| **固定闭塞防护** | 跟踪区间占用、预留、锁定、道岔资源和安全释放。 |
| **进路授权** | 只发布申请进路中的安全前缀，并在服务端强制执行。 |
| **英式信号显示** | 提供色灯信号、LED 进路指示器、重复信号和位置灯调车信号。 |
| **调度工具** | 提供游戏内控制台、可搜索 Web UI、审计记录和私有操作员令牌。 |
| **BR 红石感应器** | 根据已授权进路、接近区间和可选目标区间触发红石。 |
| **铁路标牌** | 添加可编辑的 PSR/AWI 限速牌、文字牌和信号机支架。 |

信号逻辑与显示层通过公开的设备、灯面、授权和显示接口分离，因此行车许可不会依赖信号外形、灯位排列或贴图。

## 安装

需要 Minecraft 1.20.1、Forge 47.4.18 和 MTR Forge 4.0.3。将 `mtr_brsignal_addon-0.2.0.jar` 放入客户端和服务端的 `mods/` 文件夹。

本版本使用网络协议 `11`；客户端和服务端必须使用相同的模组版本。

## 初始设置

1. 放置 MTR 信号机、轨道节点和需要使用的显示设备。
2. 手持信号机调试工具右键信号机，绑定其控制节点。
3. 使用进路工具选中信号机，再 Shift 右键目标节点。
4. Shift 右键进路指示器或重复信号，再右键主信号机完成绑定。
5. 初次完成线路拓扑后执行 `/mtrbr protection regenerate`。

## 进路与显示

色灯指示器使用 `route=1` 到 `route=6`。LED 显示器使用 `path=...`，位置灯调车信号使用 `shunt=name`。多个值可以用 `||` 组合：

```text
route=2 || path=1 || shunt=yard_1
```

LED 指示器支持数字、字母、方向和常用缩写，可用同一套信号系统描述车站、车辆段和道岔区域。

<p align="center"><img src="build/previews/path-textures-authored-preview.png" width="720" alt="LED 进路指示器预览"></p>

<p align="center"><img src="build/previews/all-route-families.png" width="720" alt="全部色灯进路指示器类型"></p>

## 调车

位置灯调车信号必须绑定主信号机和匹配的 `shunt=` 进路。开放后，主信号机可以保持红灯，而调车信号显示两盏白灯。列车通过调车入口后可以立即申请下一个闭塞；连续调车信号会逐节点推进，直到恢复普通信号授权。

<p align="center"><img src="build/previews/position-light-preview.png" width="720" alt="位置灯调车信号预览"></p>

信号每 10 tick 重算，变化会立即同步，并每 20 tick 进行一次兜底同步。移除最后一个位置灯设备后，调车模式会关闭。

## 红石列车感应器

MTR 的 `mtr:train_sensor` 默认仍使用附近列车检测；启用 BR 模式后，才会根据授权进路触发红石。必须手持信号机调试工具右键感应器才能打开设置界面，普通右键不会打开。感应器可以放在任意位置，不必位于轨道下方。

BR 模式包含两组独立绑定：**接近区间**决定列车提前多远触发；可选的**目标区间**要求列车当前前方路径经过指定区间，并且每个目标区间必须属于接近区间。

服务端会逐列车检查：列车的有效 BR 授权必须与接近区间重叠；如果配置了目标区间，同一列车当前位置之后的不可变前方路径还必须经过目标区间。因此，只进入接近区间但选择另一条分支的列车不会触发感应器。

输出模式可以是持续型 `CONTINUOUS`，也可以是脉冲型 `PULSE`。游戏内界面可设置名称、模式、输出类型和脉冲长度；选择 `BIND / WEB` 后，会打开用于地图选点的英文 Web UI。

Web UI 会列出感应器名称、维度、坐标、加载状态和输出状态，并提供 `BIND`、`CONFIRM`、`CANCEL`、`UNBIND` 与 `CLEAR ALL`。按住 Ctrl 可以多选区间。所有 Web 修改都需要经过认证的操作员令牌和权限等级 2。

## 限速牌

PSR 限速牌为红色圆牌，AWI 限速牌为黄色三角牌。两者都支持单行、双行、可编辑文字，以及左向、双向和右向箭头，并提供居中、底部和顶部安装方式。这些标牌只用于装饰，不会改变列车速度、进路或行车许可。

<p align="center"><img src="build/previews/speed_signs.png" width="720" alt="PSR 和 AWI 限速牌预览"></p>

## 文字牌与支架

文字牌使用灰色圆形边框和黑色字样。调试工具可以编辑一到三个字母或数字，文字会自动转为大写、缩放并居中。独立选项可以调整牌面和立柱安装方式。

支架提供 type (1)、type (2)、双面版本，以及 type (1) 左右护栏。type (1) 和护栏版本支持 16 个朝向，其余版本使用四个基本方向。

## 调度台与 Web UI

可以通过控制台方块或调度工具打开调度台。Web UI 提供实时拓扑地图、区间状态、信号状态、车辆申请、进路编辑和操作员控制；搜索、状态筛选、排序和完整值提示让大型网络更易管理。

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
```

Web 控制需要经过认证的操作员令牌。令牌为私有、绑定设备，并限制为权限等级 2。专用服务器需要在打开 Web UI 前配置 `web_public_host`。

## 构建与开发

构建需要 JDK 17 和 Pillow；生成位置灯预览还需要 NumPy：

```powershell
python -m pip install -r tools/requirements.txt
.\gradlew.bat build --no-daemon
```

```powershell
python tools/preview_indicators.py
python tools/preview_path_textures.py
```

详细架构说明见 [内容说明.md](内容说明.md)，版本历史见 [CHANGELOG.md](CHANGELOG.md)。

See [内容说明.md](内容说明.md) for the detailed architecture and [CHANGELOG.md](CHANGELOG.md) for release history.

## 兼容性与许可

本模组不会修改 MTR 源码或原始 JAR，而是通过模组侧扩展和 Mixins 集成。模组版本或网络协议变化时，请同时更新客户端和服务端。

源代码使用 [MIT License](LICENSE) 发布。Alte DIN 1451 Mittelschrift Regular 和 Terminus Regular 使用 SIL OFL 1.1。字体、MTR 信号柱及其他第三方来源列于 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本项目主要由 ChatGPT 实现。
