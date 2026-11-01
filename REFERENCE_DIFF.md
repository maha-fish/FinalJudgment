# 本项目 vs 示例项目 差异对照

> 对照对象：`C:\Users\Administrator\Desktop\forge-1.20.1-47.4.10-mdk-finaljudgment`（下称"示例"）
> 本项目：`C:\Users\Administrator\Desktop\forge-1.20.1-47.4.10-mdk-fj`（下称"本项目"）
> 纯静态对照（目录/类名/Mixin 配置/方法表），未改动任何代码。

---

## 一、模块与规模

| 模块 | 示例 | 本项目 | 差异 |
|---|---|---|---|
| `coremod` | **16** 个类 | 9 个 | 示例有 6 组 Patch/Transformer 对（Entity / LivingEntity / Minecraft / ServerLevel / ServerPlayer / KillTicker） |
| `agent` | 3 个（`AgentLauncher` / `InstHolder` / `JudgmentAgent`） | 13 个 | 本项目类更多，但缺 `InstHolder`（agent 侧 JVMTI 句柄持有） |
| `bootstrap` | 1 个（`FJContract`） | 2 个 | 示例有跨层契约类 `FJContract` |
| `common` | 无 | 4 个 | 本项目独有（拆出公共常量/SRG 表） |
| `main` | 85 个 | 87 个 | 数量接近 |

### 示例 coremod 类清单（16）
```
CoremodGuard, CoremodKillTicker,
EntityTransformer, LivingEntityTransformer, MinecraftTransformer,
ServerLevelTransformer, ServerPlayerTransformer, KillTickerTransformer,
JudgmentCoremodService,
EntityPatch, KillGuardPatch, KillTickerPatch, LivingEntityPatch,
MinecraftPatch, ServerLevelPatch, ServerPlayerPatch
```
> 特点：**Transformer + Patch 成对**，每个目标类一套独立改写逻辑。

### 本项目 coremod 现状（9）
单一 `CoremodTransformer` + `KillFlag` 守卫 + 注入逻辑，覆盖窄但集中。

---

## 二、Mixin 覆盖面对比

| 项 | 示例 | 本项目 |
|---|---|---|
| 总数 | 20（16 公共 + 4 client） | 19（16 公共 + 3 client） |

### 示例公共 Mixin（16）
```
MixinDataItemAccessor, MixinEntityAccessor, MixinEntityInvoker,
MixinEntityInvulnerable, MixinLivingEntity, MixinLivingEntityDead,
MixinLivingEntityDeadAccessor, MixinLivingEntityDie, MixinLivingEntityDrop,
MixinLivingEntityHealth, MixinPlayer, MixinPlayerDrop,
MixinServerGamePacketListener, MixinServerLevel,
MixinServerPlayerDie, MixinSynchedEntityDataAccessor
```

### 本项目公共 Mixin（16）
```
EntityAccessor, EntityInvoker, LivingEntityAccessor, SynchedEntityDataAccessor,
DataItemAccessor, InventorySetItemMixin,
MixinEntityInvulnerable, MixinLivingEntityHealth, MixinLivingEntityDie,
MixinLivingEntityDrop, MixinPlayerDrop, MixinPlayerAttack, MixinServerPlayerDie,
MixinUseItemPacket, MixinEntityTickCapture, MixinServerLevelSpawn
```

### 示例 client Mixin（4）
```
MixinClientPacketListener, MixinMinecraftAttackTrigger,
MixinMinecraftScreen, MixinRenderProtector
```

### 本项目 client Mixin（3）
```
MinecraftAttackTriggerMixin, MixinEntityRenderDispatcher, MixinItemInHandRenderer
```

### 缺失清单（示例有、本项目无）

| 缺失 Mixin | 作用 |
|---|---|
| `MixinLivingEntityDead` + `MixinLivingEntityDeadAccessor` | 直接操作 `dead` 字段与访问器（本项目靠 `LivingEntityAccessor` 部分覆盖） |
| `MixinPlayer` | 玩家专属注入（die / drop / attack 之外的行为） |
| `MixinServerGamePacketListener` | **服务端网络包层注入**（比本项目的 `MixinUseItemPacket` 覆盖更全） |
| `MixinServerLevel` | 服务端世界层注入（本项目 `MixinServerLevelSpawn` 只覆盖 spawn 入口） |
| `MixinClientPacketListener` | 客户端包处理入口（**双端同步**的自然落点） |
| `MixinMinecraftScreen` | 界面/屏幕层（疑似用于阻止界面打开） |
| `MixinRenderProtector` | 渲染保护（本项目有 `RenderProtector` 类，但没有对应 Mixin） |

### 本项目独有（示例无）

| 独有 Mixin | 作用 |
|---|---|
| `InventorySetItemMixin` | 防缴械（拦截 `Inventory#setItem`） |
| `MixinPlayerAttack` | 玩家攻击入口 |
| `MixinUseItemPacket` | 右键最底层兜底（`handleUseItem`） |
| `MixinEntityTickCapture` | tick 级实体捕捉（对付"自我隐藏"实体） |
| `MixinServerLevelSpawn` | 生成拦截（`addEntity` / `addFreshEntity` / `addWithUUID`） |
| `MixinEntityRenderDispatcher` | 渲染黑名单（幽灵实体不绘制） |
| `MixinItemInHandRenderer` | 第一人称格挡姿态 |

---

## 三、功能层面对比

| 能力 | 示例 | 本项目 | 判定 |
|---|---|---|---|
| coremod 结构改写 | 6 组 Patch 类 | 单一 `CoremodTransformer` | ⚠️ 本项目覆盖窄 |
| Agent 层 | `JudgmentAgent` + `InstHolder` | `AgentBridge` + `RedefineExecutor` + `NativeRoutes` | 能力接近、结构不同 |
| Native 桥 | `NativeBridge` / `NativeFieldBorrower` / `NativeHealthStore` / `NativeGuard` / `NativeLogger` / `NativeRedefineExecutor` / `BootstrapInjector` /`MethodFingerprint` / `NativeAntiAnalysis` / `NativeBytecodeUtil` / `NativeEntityKiller` | `NativeBridge` / `NativeFieldBorrower` / `NativeHealthStore` / `NativeKill` / `NativeRoutes` / `RedefineExecutor` / `ClassRestorer` / `NativeGuard` | 示例多 5 个（指纹 / 反分析 / 字节码工具 / 实体击杀器 / Bootstrap 注入） |
| 攻击层 | `AttackHandler` / `AttackEventHandler` / `KillChain` / `KillPressure` / `KillRegistry` / `KillValidator` / `RayTraceUtil` / `ServerAutoAttack` / `SilenceKillUtil` / `TrackingAttack` | 同上 + `AntiKillBypass` / `EntityRemover` / `EntityRegistry` / `SpawnVeto` / `KlassSwap` / `PositionGuard` / `PositionRestorer` / `RetrackService` / `KillPackets` / `AttackPickPacket` / `AttackRequestPacket` / `ClientAttackInput` / `DefenseTicker` / `AnchorWeaponItem` | ✅ 本项目更全 |
| 防御层 | `DefenseHandler` / `EventBusAnchor` / `EventBusGuard` / `InventorySnapshot` | `DefenseHandler` / `AntiDisarm` / `DisarmListener` / `EventBusAnchor` / `EventBusGuard` | ✅ 本项目多防缴械 |
| 客户端 | `ClientInit` / `LeftClickDetector` / `RenderProtector` | `ClientAttackInput` / `RenderProtector` | 示例有独立 `LeftClickDetector` |
| 网络 | `JudgmentNetwork` | `NetworkHandler` + `AttackPickPacket` + `AttackRequestPacket` | ✅ 本项目多心跳包 |
| core 工具 | `DeadFieldAccessor` / `DirectMemoryWriter` / `EntityFieldWriter` / `EntityStorageExtractor` / `FieldIsolation` / `ForcedClassRewrite` / `HealthFieldUtil` / `HeartbeatThread` / `IsolationManager` / `KlassManager` / `MapSweeper` / `MethodRedirect` / `PeriodicRetransformer` / `PositionWriter` / `RawDataWriter` / `ReflectionAudit` / `SectionInjector` / `SelfGuardTransformer` / `SystemStatus` / `T0Anchor` / `UnsafeAccessor` / `NativeTest*`(4) | `AttackExecutor` / `ClientRotationGuard` / `EntityListGuard` / `EntityReinjector` / `FieldIsolation` / `FieldWriter` / `HealthField` / `HeartbeatThread` / `IsolationManager` / `LogoutListener` / `LoopHijacker` / `MapSweep` / `ReflectionAudit` / `ShadowHealth` / `StorageExtract` / `StorageFieldProbe` / `SynchedEntityDataWriter` / `T0Anchor` / `UnsafeAccess` | ⚠️ 示例多 8 个专精工具 |

---

## 四、示例"更强"的四处（结构性差异）

### 1. `SelfGuardTransformer` —— 字节码级自保护

示例有专门 transformer 改写自身类，防止被其他 mod 反射/字节码攻击。
本项目只有 `EventBusGuard`（监听器级别），**没有字节码级自保护**。

### 2. `KlassManager` + `DeadFieldAccessor` + `DirectMemoryWriter` —— 类/字段/内存三件套

比本项目的 `KlassSwap` + `FieldWriter` 更底层（`DirectMemoryWriter` 直接写堆内存）。

### 3. `PeriodicRetransformer` + `ForcedClassRewrite` + `MethodRedirect`

周期性重写 / 强制改写 / 方法重定向 —— 这套是"对抗反杀 mod 改写自身"的武器。
本项目完全没有对应实现。

### 4. `NativeAntiAnalysis` + `MethodFingerprint` + `NativeBytecodeUtil`

反分析 + 方法指纹 + 原生字节码工具。
本项目只有 `NativeKill` / `NativeRoutes`。

---

## 五、结论

- **攻击层 / 防御层单点功能**：本项目更强（多出 `AntiKillBypass`（boolean 清扫 + 5 路血量灌注 + 末影龙 + multipart）、`EntityRemover`（16 路径结构移除）、`SpawnVeto`（重生拦截）、`AntiDisarm`、`EntityRegistry`（四级实体来源）、`AntiKillBypass` 等）。
- **底层对抗与自保护**：示例明显更成熟（`SelfGuardTransformer` / `KlassManager` / `DirectMemoryWriter` / `PeriodicRetransformer` / `MethodRedirect` 五件套 + 更全的包层 Mixin）。
- 感觉"示例防御更强"的真实原因：差距不在功能清单，而在**对抗基础设施**。

---

## 六、补齐建议（按优先顺序，不含实现）

| 优先 | 项 | 理由 | 预估工作量 |
|---|---|---|---|
| 1 | `MixinServerGamePacketListener` + `MixinClientPacketListener` | 双端包层覆盖，是"双端位置/物品栏同步"的自然落点 | 小 |
| 2 | `SelfGuardTransformer` | 防止本项目防护被对手改写（当前最大结构性弱点） | 中 |
| 3 | `MethodRedirect` + `PeriodicRetransformer` | 对抗"会重启 / 会自愈"的实体 | 中 |
| 4 | `MixinPlayer` + `MixinLivingEntityDead(Accessor)` | 补齐 `dead` 字段直写路径 | 小 |
| 5 | `NativeAntiAnalysis` + `MethodFingerprint` | 反分析与指纹校验 | 大 |

---

## 七、对照中发现的已知问题（记录备查）

1. **Mixin 注册静默失败**：`mixins.json` 的文本替换曾因缩进不匹配而失败，导致 `MixinEntityTickCapture`、`MixinEntityRenderDispatcher` 两轮未真正注册。已改为整份重写 json 并逐个核对 jar 内类。
2. **`Level` 没有 `addFreshEntity`**：1.20.1 的 `addFreshEntity` / `addWithUUID` / `addEntity` 都在 `ServerLevel`，Mixin 目标必须指向 `ServerLevel`。
3. **`getCustomRenderer` 在主环境不被调用**（Embeddium/Sodium 系替换物品渲染管线）→ 物品自定义渲染不可用；改用模型层 / `ItemInHandRenderer` Mixin / `getArmPose` 钩子。
