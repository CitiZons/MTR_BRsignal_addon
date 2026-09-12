# 更新记录 / Changelog

## 未发布 / Unreleased (2026-09-12)

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
