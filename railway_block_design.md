# Railway Block Design

## 当前实现状态（2026-08-26）

信号和闭塞系统已基本完成。当前运行时链路已经采用 occurrence-aware `RouteProjection`：由 immutablePath 上的当前 `FaceTraversal` 直接确定保护 boundary、PathTraversal、Section 和 JunctionMovement，并查找对应的稳定 Block definition。

`SavedBlock` 的 occurrence 映射不再决定本次授权的 boundary。找不到 `entryFace -> boundaryFace` 的稳定 Block definition 时，授权明确失败为 `BLOCK_DEFINITION_MISSING`，不会 fallback 到旧 terminal、其他 occurrence 或其他 path fingerprint。

每个 `BlockAuthorization` 保存建立授权时的完整资源快照：Block key/occurrence、Section IDs、JunctionMovement IDs 以及 path/face context。Authorization、pending physical occurrence 和 vehicle head/tail 共同决定资源生命周期；释放阶段使用快照中的精确 key，不重新推导 JunctionMovement。

资源清理规则为：Authorization 中的资源保留；Authorization 失效但车辆实体区间仍相交的 occurrence 转入 pending-release；不相交 occurrence 立即释放；两者都不存在的资源由资源级 stale 清理释放。Request 的 `DENIED`、`REVOKED` 或 `CANCELED` 状态本身不再作为持锁依据。

## 1. 调度原理运行逻辑

```text
服务端 Vehicle
  -> 读取完整 immutablePath
  -> 定位当前 Path cursor 与前方控制边界
  -> 识别 NextStoppingPoint（运营站/折返点/Depot/Terminal/临时停车点）
  -> 计算 triggerDistance
       = min(到下一运营停车点距离, 向前约 4 个信号机控制边界距离)
  -> 进入预告区域
  -> 创建 RouteRequest（基于 immutablePath 的完整进路申请 + Path fingerprint）
  -> 展开 Request 对应的有序 Section 序列
  -> 对每个 Section 查询拓扑、occupied、reserved、locked
  -> SectionCheck 生成逐段结果
       -> Path 失效/Section 冲突
            -> 若第一段即冲突，Request = DENIED
            -> Authorization 长度为 0
            -> 相关 Signal Aspect = 红
            -> Movement Gate 制动/停车
       -> 检查通过
            -> Request = WAITING
            -> Dispatcher 选择 Request（自动 FCFS 或人工选择）
            -> Authorization = Request 内当前可开放前缀
            -> Section = reserved
            -> Section = locked
            -> 多个 Signal Aspect 投影授权结果
            -> Movement Gate 允许车辆按授权进路运行
            -> 车辆车头进入 Section
            -> Section = occupied
            -> 车辆通过控制边界，Request = PASSED/ACTIVE
            -> 车辆车尾离开某个 Section
            -> 该 Section occupied/locked/reserved 独立释放
            -> 相关 Signal Aspect 重新计算
            -> 全部进路边界完成，Request = RELEASED
```

```text
人工驾驶 Vehicle
  -> 正常创建 RouteRequest
  -> 红灯或 Section occupied
  -> 服务端确认人工驾驶 Override
  -> 允许该 Vehicle 越过红灯并进入 occupied Section
  -> SectionState = occupied + manualOverride/unauthorized
  -> 自动 Vehicle 仍视该 Section 为不可进入
```

```text
Rail/Path 拓扑变化
  -> topology revision 增加
  -> Path fingerprint 失效
  -> Authorization 撤销
  -> reserved/locked 失效
  -> 真实 occupied 保留
  -> Movement Gate 停车
   -> 重新生成 Path 和新的 RouteRequest
  -> 默认不自动换路
```

所有安全相关逻辑运行在 MTR simulation 服务端线程。客户端只接收 `Signal Aspect + Authorization ID + Revision` 并显示。

## 2. 总体链路

闭塞计算、调度、授权和车辆运行控制全部在服务端完成；客户端只接收结果并显示。

```text
Vehicle
  -> 接近控制区域
  -> RouteRequest（一列车申请一条完整进路）
  -> SectionCheck（逐段检查完整 Section 序列）
  -> Dispatcher（自动 FCFS 或人工选择）
  -> Authorization
       -> Signal Aspect
       -> Movement Gate
```

Signal Aspect 是 Authorization 的表现，不负责选择车辆；Movement Gate 才是实际阻止未授权车辆运行的执行层。

## 3. 闭塞块与 SectionState

- 闭塞块由相邻 SignalFace 绑定节点切分，块内包含一条或多条 Rail。
- 闭塞块无方向，A-B 与 B-A 相同。
- 安全/锁闭/占用以闭塞块为单位；方向属于 Signal Face、Route、Path 和 Vehicle。

SectionState 是服务端权威状态库，按 Section 保存三类独立事实：

- `occupied`：车辆实际占用；
- `reserved`：Dispatcher 已批准、准备使用；
- `locked`：进路已锁闭，禁止冲突操作。

三者不是互斥枚举。车辆进入后可以同时处于 `locked + occupied`；未经授权的异常车辆也可能处于 `occupied`。

占用由 Vehicle movement / `VehiclePosition` 更新；预留和锁闭由 Authorization 管理。拓扑变化时不得清除真实占用。

## 4. RouteRequest

一个普通 Vehicle 同时只有一个 active RouteRequest。Request 对象是基于 `immutablePath` 的一条完整进路申请，而不是一个信号边界；Authorization 是同一 Request 当前实际获批并锁闭的前缀，且 `Authorization ⊆ Request`。

Request 至少包含：

- Vehicle identity；
- Route / Path fingerprint 与 generation；
- 有序 Section 序列及行进方向；
- 进路中的 Signal Face 序列；
- Terminal、Depot、折返点等终点信息；
- 创建时间、当前状态和失效原因。

Request ID 建议为：

```text
Vehicle + Route/Path fingerprint + request generation
```

Path 变化时创建新 generation，不复用旧 Request。未来提前申请下一进路时，再增加独立的预请求机制。

### Request 状态

```text
NONE        // 无请求
APPROACHING // 已进入控制区域预告范围
REQUESTED   // 已提交进路请求
CHECKING    // 正在执行 Section Check
WAITING     // 检查有效，等待 Dispatcher 选择
AUTHORIZED  // 已获得授权，尚未进入进路
ACTIVE      // 车辆已进入授权进路
PASSED      // 车头已通过目标控制边界
RELEASED    // 车尾已离开相关 Section，资源已释放
DENIED      // 被拒绝
REVOKED     // 已授权但被撤销
INVALID     // Path 或拓扑已失效
CANCELED    // 请求被车辆或调度取消
```

`PASSED` 不等于 `RELEASED`。锁闭释放必须等待车尾离开授权 Section。

## 5. Request 触发与 Next Stopping Point

Request 由 Vehicle 根据自身完整 `immutablePath` 触发，不由 Signal 扫描全体车辆。

`NextStoppingPoint` 只用于判断预告范围，不直接使用 MTR 运行时 `stoppingPoint`。应从完整 Path 元数据区分：

- 运营站；
- 折返点；
- Depot；
- Terminal；
- 临时停车点。

车辆进入前方控制边界的预告区域后创建 Request；Request 对应完整进路，Authorization 只按当前可安全开放的 Section 前缀动态扩展和释放。

## 6. SectionCheck、Dispatcher 与 Authorization

SectionCheck 返回每个 Section 的详细结果，而不是简单 boolean：

```text
sectionId, direction, topologyStatus,
occupiedBy, reservedBy, lockedBy, reason
```

任意 Section 不存在、拓扑 fingerprint 不匹配、被冲突车辆占用或被冲突进路锁闭，检查结果为 unsafe。

Dispatcher 只负责选择 Request：

- 自动模式使用 FCFS；
- 人工模式由调度员选择合法 Request；
- 人工选择不能绕过 SectionCheck、拓扑验证或 Movement Gate。

这里的 `Dispatcher Override` 与人工驾驶 Override 不同：前者只改变调度优先级；后者是人工驾驶车辆的特殊运行权限，可以突破红灯和 occupied 检查，但不能改变其他车辆的 Aspect 或 Authorization。

Authorization 可以覆盖多个 Signal Aspect，且长度可以小于 Request。Signal 读取对应授权的派生结果；没有有效 Authorization 时默认红灯。

## 7. 拓扑失效与执行层

```text
Rail/Path topology change
  -> Path 失效
  -> Authorization 撤销
  -> Movement Gate 制动
  -> Vehicle 原地停车
  -> 等待重新生成 Path 或人工处理
```

不自动重新寻路。重新生成 Path 后必须创建新的 fingerprint 和 Request。

拓扑变化可以撤销 `reserved`/`locked`，但不能删除 Vehicle 的真实 `occupied` 状态。

## 8. 更新与性能原则

- 每个 MTR simulation tick：更新 VehiclePosition、SectionState，并执行已缓存 Authorization 的 Movement Gate。
- 事件触发：车辆生成/删除、进入预告区、跨越 Section、占用变化、拓扑变化、授权变化。
- 周期任务：处理 dirty Request、运行 Dispatcher、同步客户端状态；约每秒一次即可。
- 不在 Request 创建时扫描全体车辆。
- 建立 `Section -> Request`、`Signal Face -> Request`、`Vehicle -> Request` 反向索引，只重算受影响 Request。
- 缓存 Path Section 序列、累计距离、Signal 拓扑关系、Path fingerprint 和 topology revision。

## 9. 实施大纲

1. 服务端拓扑 revision、Rail/Section 映射和 SectionState。
2. 服务端活车索引、完整 Path 展开和 fingerprint 校验。
3. RouteRequest 状态机与预告区域触发。
4. SectionCheck 详细报告和受影响 Request 索引。
5. FCFS Dispatcher、Authorization、reserved/locked 生命周期。
6. 多 Signal Aspect 的服务端计算与客户端同步。
7. Movement Gate，强制执行授权和拓扑失效停车。
8. 调度面板、人工 Dispatcher、审计与异常恢复。

## 10. 已确定的设计决策

1. **Request 触发距离**：`min(到下一运营停车点距离, 向前约 4 个信号机控制边界距离)`。制动距离只属于 Movement Gate 的安全约束。
2. **授权范围**：一个 RouteRequest 对应一条完整进路；Authorization 是它的当前可开放前缀，范围为 `0..Request 长度`，可投影到多个 Signal Aspect。
3. **Request 重试**：`WAITING`/`DENIED` 在相关 SectionState 变化时立即重新检查，并由周期任务兜底；不使用固定等待时间。
4. **FCFS 排序**：剩余 Path 距离、Request 创建 tick、Vehicle ID；人工 Dispatcher 可覆盖优先级。
5. **进路结束边界**：由 RouteRequest 定义；普通情况为信号边界，特殊情况为 Terminal、Depot 或折返点。
6. **Section 释放**：车尾离开某 Section 即独立释放该 Section，并重新计算相关 Signal Aspect。
7. **拓扑失效**：撤销 Authorization，Movement Gate 停车，重新生成 Path 和 Request；默认不自动换路。
8. **人工调度**：可修改优先级、批准等待请求、撤销授权；撤销已有授权必须经过安全流程。
9. **手动驾驶**：仍创建普通 Request；人工驾驶 Override 可使该车越过红灯并进入 occupied Section，Section 仍记录 `occupied=true` 与 Override/异常状态，自动车辆不得进入。
10. **Signal Face**：服务端维护信号位置、作用节点、方向、控制边界和 Route 关系；沿用 addon 现有绑定逻辑作为基础。
11. **同步协议**：客户端接收 `Signal Aspect`、`Authorization ID` 和 `Revision`，只负责显示。
12. **持久化**：世界保存 Signal 绑定、Route 配置和调度设置；Request、Authorization、SectionState 只存在运行时。
13. **线程模型**：SectionState、Dispatcher、Authorization 和 Movement Gate 全部运行在 MTR simulation 服务端线程；客户端只显示和发送调度操作。
