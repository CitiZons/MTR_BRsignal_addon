# 复示信号与下挂模型

- `banner_repeating_signal_on/off/off_limiting.json` 与 `textures/block/repeating_signal/` 是手工源资源，不要覆盖。
- 复示信号运行时读取源模型里引用 `block/repeating_signal/` 的面，使用其位置、UV 和元素旋转全亮绘制；资源重载会清空面缓存。
- `on` = 未绑定或主信号红灯，`off` = 主信号绿灯，其余（包括已绑定但无服务端状态）= `off_limiting`。
- 调试工具 Shift+右键显示设备，再 Shift+右键 MTR 主信号绑定；普通右键查看/解绑/切换安装方式。
- `hanging=false` 保留原下方支撑。`hanging=true` 向上平移外壳并接到 MTR 信号柱中心，不翻转图案或 route。点击支撑物底面直接下挂。安装切换沿用调试工具的管理员权限。

在项目根目录运行：

```powershell
python tools/generate_signal_mounts.py
python tools/check_triple_indicators.py
python tools/check_six_route_indicators.py
python tools/check_signal_mounts.py
.\gradlew.bat build --offline --no-daemon
```

下挂生成器同时更新方块状态、旋转模型、信号机支架的三种斜向模型与 `IndicatorMountGeometry.java`，保留全部手工 PNG。支架斜向模型以其后方格中心 `(8, 0, -8)` 为旋转轴，而不是碰撞方块中心。修改源模型外壳后须重新生成并构建；只替换复示贴图可直接资源重载。
支架的 0° 变体在原有方块状态旋转基础上额外转 180°；三个斜向变体沿用已有变换。
`generate_triple_indicators.py`、`generate_six_route_indicators.py` 的命令行入口也会自动更新下挂资源。若运行较早的 `generate_indicator_rotations.py`，须继续运行现有色灯资源整理流程及下挂生成器；不要用其历史烘焙亮灯方块状态替代当前动态渲染状态。

## 新支架与调车模型（2026-09-12）

当前注册支架为 `signal_bracket_1`、`signal_bracket_2`、各自的 `double_side` 以及 `signal_bracket_1_left/right`。原有下挂生成器的 `bracket()` 仍只处理旧 `signal_bracket` 资源；不能用其输出替代新型号。新 (1) 和护板斜向模型沿用后方格中心 `(8, 0, -8)`，其方块状态朝向与 (1) 对齐；(2) 与双侧仅四方位。

用户修改调车母版后，运行 `python tools/generate_position_light_signals.py` 同步派生模型与碰撞箱，再运行 `python tools/check_position_light_signals.py` 校验并更新预览。母版自身保留不覆盖。
