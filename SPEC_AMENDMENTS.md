# 规范修订记录（已获授权，2026-10-18）

本文件是 4 条修订的**权威记录与逐字 diff**。两份真相源（ENV_SPEC.md / IMPLEMENTATION_SPEC.md）
的对应行需按下列「改为」逐条替换；替换由 AI 执行，执行后本文件的第 3 列即为现行文本。

---

## 修订 1：`FMLModType` 必须 `MODLAUNCHER`（原 `GAMELIBRARY`）

| 位置 | 原文 | 改为 |
|---|---|---|
| ENV_SPEC 4.2 | `MANIFEST.MF 声明 FMLModType: GAMELIBRARY` | `MANIFEST.MF 声明 FMLModType: MODLAUNCHER` |
| ENV_SPEC 4.2 常见错误 2 | （无） | 追加：`GAMELIBRARY 只把 jar 当游戏库：服务会被加载、但 ITransformer 永不参与类变换（实测 transformHits=0）` |
| IMPL 2.3 + Unit 5 | `FMLModType: GAMELIBRARY` | `FMLModType: MODLAUNCHER` |

**证据**：`GAMELIBRARY` 下 `fj.coremod.transform.hits = 0`（服务 onLoad 正常、transformer 从不被调用）；
改 `MODLAUNCHER` 后 `transformHits = 8→10`，`Minecraft` / `Entity` / `LivingEntity` / `ServerPlayer` / `Player` 全部注入成功。

---

## 修订 2：规范 1 例外 —— coremod 允许持有最小本地 SRG 候选副本

| 位置 | 原文 | 改为 |
|---|---|---|
| IMPL 规范 1 | `SRG 名只在 common 映射表出现` | `SRG 名只在 common 映射表出现；例外：coremod 可持有**仅覆盖其注入点**的最小本地副本（当前 9 键，MCP+SRG 双写）` |

**理由**：MC 类（`Entity`/`LivingEntity`）在主 mod **之前**被变换，此刻 transformer 的 context CL
看不到 mod 类，属性桥亦未写入，反射与属性两条路在时序上必然失败
（实测 `ClassNotFoundException: com.maha_fish.finaljudgment.common.SRGNames`）。

---

## 修订 3：规范 21 —— `KillFlag` 必须住在 coremod（game 层）

| 位置 | 原文 | 改为 |
|---|---|---|
| IMPL 3.2 功能 4 | `KillFlag 类放在 common` | `KillFlag 放在 coremod 包（com.maha_fish.finaljudgment.coremod.KillFlag）；主 mod 通过 KillFlagBridge 反射写入` |
| IMPL 规范 21 | `KillFlag 必须唯一加载` | 追加：`且必须位于 game 层（coremod jar）；放在 common 会导致 MC 类解析不到 → NoClassDefFoundError 被守卫 try-catch 吞掉，表现为「生物死亡不消失、持续掉战利品」` |

**证据**：实测 `生物死亡不消失 + 持续爆掉落物/经验`，根因是 `Entity`（game 层）解析 `common/KillFlag`（mod 层）失败；
迁到 coremod 后恢复正常。

---

## 修订 4：删除 7.4.1 的 `IMPL_LOOKUP` 备用路径

| 位置 | 原文 | 改为 |
|---|---|---|
| IMPL 7.4.1 功能 1 | `主路径反射 theUnsafe，备用 IMPL_LOOKUP` | `主路径：MethodHandles.privateLookupIn(sun.misc.Unsafe.class, lookup).findStaticGetter(...)`；**不设 IMPL_LOOKUP 备用**（读它还须 setAccessible，与规范 30 冲突）；失败则 WARN + 降级 |
| IMPL 规范 30 | （不变） | 追加：`sun.misc.Unsafe 在 JDK 17 由 jdk.unsupported open，零参数可用` |

**证据**：`[FJ] Unsafe 就绪: available=true 路径=privateLookupIn(sun.misc.Unsafe).findStaticGetter`（零 JVM 参数）。

---

## 修订 5：Unit 9a 目标选取的行为调整（用户要求）

| 项 | 原文 | 改为 |
|---|---|---|
| 远程距离 | `REMOTE_DISTANCE = 32.0` | `REMOTE_DISTANCE = 64.0`（Constants） |
| Shift+左键 | 无此行为 | **无限距离选目标**（`RayTraceUtil.pickUnlimited`，夹角阈值与普通左键一致 = `MAX_COS_ANGLE` 0.80） |

---

## 修订 6：Unit 9a 新增两个类（`AttackPickPacket` / `ClientAttackInput`）

| 位置 | 原文 | 改为 |
|---|---|---|
| IMPL Unit 9a 产出物 | 仅 `AttackHandler` / `AttackEventHandler` / `RayTraceUtil` | 追加 `AttackPickPacket`（包 ID 0）与 `ClientAttackInput`（`InputEvent.InteractionKeyMappingTriggered`） |

**理由**：`LeftClickEmpty` 是**纯客户端**事件（实测 29 次全在 `Render thread`），客户端拿不到服务端实体表；
原版 `ServerboundInteractPacket` 在服务端有 8 格校验，远距离会被丢弃。因此远距离/右键选取**必须**自建请求包。

**证据**：接入后日志出现 `命中入口=右键-远程 距离=5.4…11.4`、`命中入口=Shift+左键 距离=9.7…12.3`（均在 `Server thread`）。

---

## 执行状态

- [x] 修订内容定稿（本文件）
- [ ] 写入 ENV_SPEC.md（2 处）
- [ ] 写入 IMPLEMENTATION_SPEC.md（修订 2/3/4 各 1–2 处）
