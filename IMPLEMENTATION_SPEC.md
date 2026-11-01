终焉裁定 —— 项目实现指南（修订版·已修复·补充完整·顺序修正版）

目标：Minecraft Forge 1.20.1
面向：从零实现本项目的开发者
警告：本文档仅限单机、授权测试环境或技术研究使用。
以下内容已修正原文档中会导致“开发环境能跑、生产环境静默失效”或“类加载失败 / VerifyError”的关键错误。

---

第一部分：项目定位与设计原则

1.1 项目目标

提供一个“秒杀武器”，让它能在任何防御下让目标实体事实消失，同时保护持有者不被反制。

1.2 为什么需要多层

单层机制的弱点：

单层 弱点
纯 Forge 事件 对方取消事件即可绕过
纯 Mixin 对方抢占优先级或移除注入
纯 Coremod 对方也用 Coremod 覆盖
纯反射 对方覆写方法即可拦截
纯 Native 只有这一层时一旦被禁全盘失效

答案：多层纵深。每层独立运行，任一层被破不影响其他层。目标是最强的那一层（Native JVMTI）能兜底。

1.3 六大设计原则

原则 目的
1 层职责单一 同一方法只由一层修改，避免互相破坏
2 失败隔离 任一路径失败不阻塞其他路径
3 SRG 名贯穿 生产环境字节码是 SRG 名，不是 Mojang 名
4 打包独立 Coremod / Agent 不能通过 jarJar 加载
5 Native 优先 核心状态存 Native 层，绕开 Java 堆扫描
6 单向依赖 上层可调下层，下层不知道上层

补充原则：

原则 目的
7 跨 ClassLoader 不共享静态字段 同名的 common 类被不同 ClassLoader 加载后不是同一个 Class，静态字段不共享
8 Bootstrap 类不引用 MC / mod 类 Bootstrap ClassLoader 看不到 MC 类和 mod 类
9 按返回类型生成守卫 void 用 RETURN，boolean 用 ICONST_0/1 + IRETURN
10 基线按类来源选择 MC 类不能用 jar 原始字节作基线，自身类可用 jar 版本
11 标准路径不加 JVM 参数 走 Unsafe 直写 + IMPL_LOOKUP + 标准 JDK，dev 和生产都不需要 --add-opens
12 禁止 setAccessible 访问 java.base 改用 Unsafe 直写；setAccessible 会引入 JVM 参数依赖

---

第二部分：构建产物

2.1 五类产物

产物 类型 安装位置
主 mod jar 普通 mod mods/
Coremod jar -transformer 后缀的独立 jar mods/
Agent jar 独立 jar 嵌入主 mod 的 META-INF/agent/
Native 库 dll / so / dylib 嵌入主 mod 的 META-INF/native/<platform>/
Bootstrap jar 独立 jar 嵌入主 mod 的 META-INF/bootstrap/

2.2 用户安装

把两个 jar 放进 mods/ 目录即可。Agent、Native、Bootstrap 由主 mod 自动提取。

命名规范：

· 主 mod：finaljudgment-<version>.jar
· Coremod：finaljudgment-coremod-transformer.jar（-transformer 必须严格小写，且是文件名的结尾）
· 两个文件名前缀应保持一致，便于用户识别
· 用户不能改名去掉 -transformer 后缀，否则 Coremod 完全不加载

2.3 实现建议

目标：让 5 个产物正确加载到正确的 ClassLoader。

Coremod 独立 jar + -transformer 后缀

· 必须：jar 名以 -transformer 结尾
· 必须：MANIFEST.MF 里声明 FMLModType: MODLAUNCHER
  （修订：GAMELIBRARY 只把 jar 当游戏库，服务会加载但 ITransformer 永不参与类变换，实测 transformHits=0）
· 注意：mods.toml 没有 modType 字段。Coremod 类型由 MANIFEST.MF 的 FMLModType 决定，不要写 modType="GAMELIBRARY"。
· 必须：META-INF/services/cpw.mods.modlauncher.api.ITransformationService 下有声明文件
· 不能：用 jarJar 打包——Forge 只扫 mods/ 根目录，看不到嵌套 jar 里的 services

mods.toml 最小模板：

```toml
modLoader="javafml"
loaderVersion="[47,)"
license="All rights reserved"
[[mods]]
modId="finaljudgment_coremod"
version="1.0.0"
displayName="FinalJudgment Coremod"
```

META-INF/services/cpw.mods.modlauncher.api.ITransformationService 内容：

```text
com.maha_fish.finaljudgment.coremod.JudgmentCoremodService
```

Agent 嵌入主 mod

· 从 META-INF/agent/ 提取到临时文件，启动子 JVM 附加
· 必须：MANIFEST.MF 三件套 Main-Class / Premain-Class / Agent-Class
· 必须：Can-Redefine-Classes: true / Can-Retransform-Classes: true
· 不能：用 VirtualMachine.attach(自身 PID)——JDK 9+ 禁止自附加（ALLOW_ATTACH_SELF 是编译期常量）
· 技术点：子 JVM java -jar agent.jar <pid> 是合规路径。标准 JDK 17 默认包含 jdk.attach 模块，不需要 --add-modules。只有 jlink 裁剪过的 JRE 才缺失，此时回退 -javaagent。
· 注意：给 run 配置加 -Djdk.attach.allowAttachSelf=true 是冗余的（子 JVM 附加父 JVM 不算 self-attach），加上无害但会误导后来者

Native 嵌入主 mod

· 按 os.name / os.arch 提取对应版本
· 本项目仅编译 Windows x86_64。其他平台留作扩展方向（见 6.5 节扩展方向 1）。
· 技术点：System.mapLibraryName 处理平台差异

Bootstrap 嵌入主 mod

· 从 META-INF/bootstrap/ 提取到临时文件
· 由 Native 层的 AddToBootstrapClassLoaderSearch 注入到 Bootstrap ClassLoader
· 必须：Bootstrap 是独立 submodule，独立打成 jar
· 必须：jar 里只能有常量和基础类型，不能 import 任何 MC / Forge / mod 类
· 能引用：JDK 全部类（java.*、javax.*、jdk.*）
· 不能引用：net.minecraft.*、net.minecraftforge.*、com.maha_fish.finaljudgment.*
· 用途：让所有 ClassLoader（System / ModLauncher / TransformingClassLoader / 各 mod 的）都能通过 Class.forName(name, false, null) 访问同一份常量
· 承载内容：锚点 key / value 常量、系统属性名、跨 ClassLoader 可见的开关标志
· 约束：一旦注入不可撤回（JVM 生命周期内）
· 自检：
  · Bootstrap jar 里有几个类？是否全部只有常量？
  · 用 javap 检查，是否有任何 MC / Forge 类型的引用？
  · 主模块能通过 ClassLoader.getSystemClassLoader().getParent() 读到这些常量吗？

2.4 各产物的 ClassLoader

产物 由哪个 ClassLoader 加载 能引用什么 注入的字节码由谁解析
Bootstrap jar Bootstrap ClassLoader 只能 JDK 类（java.*、javax.*、jdk.*） —
Coremod jar ModLauncher 给每个 mod 的 TransformingClassLoader 实例 只能 JDK / ModLauncher API 目标类的 ClassLoader
Agent jar System ClassLoader 只能 JDK 目标类的 ClassLoader
Native 库 由调用方加载 C 层，无 Java 约束 —
主 mod ModLauncher 的 TransformingClassLoader MC / Forge / 各 mod 自身类 自身类的 ClassLoader

注：Coremod 的 ITransformationService 由服务层加载；注入到目标类的字节码由目标类的 ClassLoader 解析。不要假设跨层能看到同一个 Class 对象。

---

第三部分：Common 模块

3.1 功能目标

提供所有模块共用的契约。

3.2 需要实现的功能

功能 1：SRG 名集中管理

· 效果：所有方法名 / 字段名的 Mojang ↔ SRG 映射在一处定义，其他类只引用不硬编码
· 实现建议：
  · 静态工具类，public static final String[] 数组保存双候选
  · 提供 match(String name, String[] candidates) 工具方法
  · 关键：这是唯一写 SRG 名的位置
  · 加强：对字段，光有双候选不够。必须提供“按描述符从 ClassNode 解析实际名字”的工具：resolveField(ClassNode, String[] candidates, String desc)。因为 ModLauncher 传的字节码里字段名可能是 Mojang 名、SRG 名，或被进一步混淆的中间态。单纯的 "removalReason".equals(f.name) || "f_146795_".equals(f.name) 会在某些加载阶段失败。
  · resolveField 必须四级回退：
    1. 名字 + 描述符都匹配
    2. 只名字匹配
    3. 只描述符匹配
    4. 关键字模糊匹配（含 removal / 146795 / 描述符含 RemovalReason）
  · 首次失败时必须打印：
    · 目标类的所有字段名 + 描述符
    · 期望的描述符
    · 期望的候选名列表
    · 用 boolean 标志避免刷屏（一次性诊断）

功能 2：全局常量

· 效果：MOD_ID、锚点 key、系统属性名、资源路径统一管理
· 实现建议：静态常量类
· 约束：锚点 key 和 value 必须在 Bootstrap jar 里也有一份副本（值相同），供跨 ClassLoader 访问

功能 3：状态接口

· 效果：Agent / Coremod / Native 三层都能被统一查询
· 实现建议：接口 isReady() + getName()

功能 4：KillFlag（跨层通信开关）

· 效果：Coremod 生成的字节码直接读字段，不经过任何方法调用——对方 hook 任何 Java 方法都无效
· 实现建议：
  · public static volatile Object targetEntity（攻击目标）
  · public static volatile Object protectedPlayer（防御目标）
  · 引用相等语义：null 时任何 this 都不等于它，检查自动跳过
· 必须：KillFlag 放在 **coremod 包**（com.maha_fish.finaljudgment.coremod.KillFlag）；主 mod 通过 KillFlagBridge 反射写入
  （修订：放 common 会让 game 层的 MC 类解析不到，实测「生物死亡不消失 + 持续掉战利品」）
· 重大修正：必须保证 KillFlag 由同一个 ClassLoader 加载且只加载一份。Coremod jar 不要打包主 mod 的 common 类，否则静态字段不共享，注入的 GETSTATIC 会读到另一个副本。
· 构建脚本约束：Coremod 的构建配置必须排除主模块的 common 包
· 已知缺陷：targetEntity 是单值，AOE 攻击多目标时会互相覆盖（后一个覆盖前一个）。改进方向：改成 Set<Object> + WeakHashMap，同时提供 isTarget(Object) 方法供 Coremod 字节码调用。当前实现作为 MVP 可用。

3.3 关键技术点

为什么 SRG 名要集中：历史教训——分散硬编码导致某处漏改，生产环境静默失效（开发环境用 Mojang 名能跑，运行时找不到方法）。

完整 SRG 名对照表（每个都要双候选）：

类 字段 / 方法 Mojang 名 SRG 名
Entity removalReason removalReason f_146795_
Entity position position f_19825_
Entity blockPosition blockPosition f_19826_
Entity tick tick m_8119_
Entity setRemoved setRemoved m_142467_
Entity remove remove m_142687_
Entity discard discard m_146870_
Entity isRemoved isRemoved m_213877_
Entity isInvulnerableTo isInvulnerableTo m_6673_
Entity isInvulnerable isInvulnerable m_6063_
Entity fireImmune fireImmune m_5825_
LivingEntity getHealth getHealth m_21223_
LivingEntity setHealth setHealth m_21153_
LivingEntity getMaxHealth getMaxHealth m_21233_
LivingEntity hurt hurt m_6469_
LivingEntity die die m_6667_
LivingEntity kill kill m_6074_
LivingEntity isAlive isAlive m_6084_
LivingEntity isDeadOrDying isDeadOrDying m_21224_
LivingEntity dropAllDeathLoot dropAllDeathLoot m_6668_
LivingEntity dead dead f_20861_
Mob target target f_21340_
Player dropEquipment dropEquipment m_36232_
ServerLevel entityManager entityManager f_143244_
ServerLevel entityTickList entityTickList f_143243_ ⚠️
ServerLevel navigatingMobs navigatingMobs f_143246_ ⚠️（易混）
ServerChunkCache chunkMap chunkMap f_8325_ / f_8364_
ChunkMap entityMap entityMap f_140150_ / f_140166_
Minecraft runTick runTick m_91383_

⚠️ 最易错的一行：entityTickList 的 SRG 名是 f_143243_，不是 f_143246_。f_143246_ 是 navigatingMobs，混用会导致清 ticklist 失败，表现为“实体从 ticklist 消失但逻辑上还活着”。

开发环境 vs 生产环境差异清单：

项 开发环境 生产环境
字节码名字 Mojang 名 SRG 名
FMLEnvironment.production false true
Mixin refmap 需要 自动映射
getHealth 名称 getHealth m_21223_
removalReason 字段名 removalReason f_146795_

3.4 自检清单

· □ 全项目搜 "f_" 开头的字符串，是否只在映射表里出现？
· □ resolveField 有没有四级回退？
· □ 失败时打印的是“我找不到字段”还是“目标类有哪些字段”？
· □ Coremod 构建产物里有没有 common 包？
· □ 用 javap 检查 Coremod jar 内容，确认没有重复类
· □ 描述一下“如果 KillFlag 被加载两份，会看到什么现象？”

---

第四部分：Agent 层

4.1 功能目标

在 JVM 启动早期注册 ClassFileTransformer，允许主 mod 动态改类。

4.2 需要实现的功能

功能 1：跨 ClassLoader 传递 Instrumentation

· 效果：主 mod（ModLauncher ClassLoader）能拿到 Agent（System ClassLoader）持有的 Instrumentation
· 实现建议：
  · Agent 侧用一个静态类持有 Instrumentation（InstHolder）
  · 主 mod 侧通过反射加载 Agent 的类，Class.forName 时显式指定 ClassLoader.getSystemClassLoader()
  · 关键：两个 ClassLoader 不能直接引用类型，只能反射

功能 2：动态附加

· 效果：用户无需配置 -javaagent 参数
· 实现建议：
  · 主 mod 从自身 jar 提取 agent jar 到临时文件（AgentAttacher.extractAgentJar）
  · 先检测 jdk.attach 是否可用（尝试 Class.forName("com.sun.tools.attach.VirtualMachine")）
  · 启动子 JVM java -jar agent.jar <当前PID>
  · 子 JVM 用 com.sun.tools.attach.VirtualMachine.attach(pid) 附加到父进程
  · 子 JVM 调 vm.loadAgent(jarPath) 触发父 JVM 的 agentmain
  · 关键：不能用 VirtualMachine.attach(自身 PID)——会被 ALLOW_ATTACH_SELF 拒绝
  · 修正：标准 JDK 17 默认包含 jdk.attach，不需要 --add-modules。只有 jlink 裁剪过的 JRE 才缺失，此时回退 -javaagent。检测 VirtualMachine 是否可加载，不可用时提示用户使用完整 JDK 或回退 -javaagent。
  · 附加超时时间：10 秒
  · 附加后轮询等待 Instrumentation 到位（每 100ms 检查一次，最多 5 秒）

功能 3：定时校验

· 效果：每 5 秒对核心类 retransformClasses，触发所有注册的 Transformer
· 实现建议：
  · 用 ScheduledExecutorService
  · 在 retransform 前后调 Native 侧的“我方标记”接口（nativeSetSelfRetransform(true/false)）
  · 关键：不标记的话，Native 层会把它当成外部篡改
  · 修正：Agent 由 System ClassLoader 加载，不能直接引用主 mod 的 NativeBridge。必须通过反射、系统属性或共享 Bootstrap 类传递标记。
  · 注意：Agent jar 由 System ClassLoader 加载，getClassLoader() 拿不到 ModLauncher 的 TransformingClassLoader 加载的 MC 类。所以任何 Class.forName(mcClassName) 必须用 Thread.currentThread().getContextClassLoader()

功能 4：ForcedClassRewrite（Mixin 之后的最后一道改写）

· 效果：Mixin 应用完后，Omni 可能在 LivingEntity / Entity 里检测到 Mixin 并撤销注入。本模块通过 Instrumentation 在 Mixin 之后抢一层 retransform
· 实现建议：
  · 注册一个自己的 ClassFileTransformer 到 Instrumentation
  · 在 Mixin 完成注入后，立即对 Entity / LivingEntity 调 retransformClasses
  · 时间点在 Omni 的检测逻辑之后
  · 关键：ClassFileTransformer.transform 要幂等，避免反复改
· 与 Native RedefineClasses 的区别：
  · ForcedClassRewrite 用 Instrumentation.retransformClasses，走 ModLauncher 官方 transformer 链，允许用于 vanilla 类；
  · Native RedefineClasses 用 JVMTI，绕过 transformer 链，禁止用于 vanilla 类；
  · 两者目标类不重叠：ForcedClassRewrite 处理 vanilla，Native Redefine 处理第三方类。

4.3 关键技术点

技术点 说明
子 JVM 附加 绕过 JDK 9+ 的自附加禁令；标准 JDK 默认包含 jdk.attach
MANIFEST 三件套 缺任一个都静默失败
我方标记 nativeSetSelfRetransform(true/false) 包裹 retransform 调用；跨 ClassLoader 必须反射或走共享层
轮询等待 附加后每 100ms 检查一次，最多 5 秒，因为 agentmain 是异步的
Context ClassLoader Agent 层的 ASM getCommonSuperClass 必须用 Thread.currentThread().getContextClassLoader()
COMPUTE_MAXS / COMPUTE_FRAMES COMPUTE_MAXS 只算 maxStack/maxLocals，不生成栈帧，也不调用 getCommonSuperClass。插入分支必须用 COMPUTE_FRAMES \| COMPUTE_MAXS
我方标记传递 跨 ClassLoader 传递必须走反射 + Bootstrap 共享类，不能直接引用主 mod 的 NativeBridge
retransform 与 Redefine 区别 Instrumentation.retransformClasses 允许 vanilla；JVMTI RedefineClasses 禁止 vanilla

4.4 自检清单

· □ 附加前有没有检测 jdk.attach？
· □ 附加失败时，日志能看出是哪一步失败吗？
· □ 定时 retransform 前后，有没有调“我方标记”？
· □ 我方标记是通过什么方式跨 ClassLoader 传递的？
· □ ASM 的 getCommonSuperClass 用的是哪个 ClassLoader？
· □ ForcedClassRewrite 用的是 Instrumentation 还是 JVMTI？

---

第五部分：Coremod 层

5.1 功能目标

在 ModLauncher 加载阶段介入字节码，优先于其他 mod。

5.2 需要实现的功能

功能 1：服务注册

· 效果：ModLauncher 能发现并加载我们的 ITransformationService
· 实现建议：
  · 实现 ITransformationService 接口
  · 提供 META-INF/services/ 声明文件
  · 关键：服务名是 cpw.mods.modlauncher.api.ITransformationService
  · onLoad 里设系统属性 fj.coremod.loaded=true 让主 mod 能检测 coremod 是否加载

功能 2：目标类字节码注入

· 效果：向 MC 关键类注入守卫逻辑
· 实现建议：
  · 每个目标类一个 ITransformer<ClassNode> 实现
  · targets() 返回目标类名
  · transform() 调对应的 Patch 类
  · 关键：所有方法名 / 字段名匹配必须从 ClassNode 里解析实际名字（不是硬编码双候选）

功能 3：守卫注入

· 目标方法：
  · Entity.setRemoved：检查保护标志
  · LivingEntity.setHealth / hurt / kill / die：同
  · ServerPlayer.die / dropEquipment：同
  · ServerLevel.removeEntityComplete：同（仅当 Forge 提供该方法时注入；找不到时打 INFO 日志，跳过，不报错）
  · Minecraft.runTick：调 tick hook（仅客户端，Coremod 必须分侧）
· 实现建议：
  · 纯字节码比较：if (this == KillFlag.protectedPlayer) return;
  · 修正：必须按方法返回类型生成不同 return：
    · void → RETURN
    · boolean → ICONST_0; IRETURN 或 ICONST_1; IRETURN
    · 其他类型按需生成默认值
  · 不调用任何 Java 方法（对方 hook 不到）
  · try-catch 包裹整个守卫块
  · 字段解析规则：
    · 每个 Patch 类都要有 resolveField(ClassNode, String[] candidates, String desc) 方法
    · 匹配优先级：名字 + 描述符 → 只名字 → 只描述符 → 关键字模糊匹配
    · 每次首次调用打印诊断日志（找到 / 找不到），避免刷屏
  · 不能“两个名字都写、一个抛异常兜底”：PUTFIELD 字段名不匹配是链接期 NoSuchFieldError，虽然 catch (Throwable) 理论上能捕获，但会掩盖问题且可能已处于不一致状态。必须从 ClassNode 解析实际字段名和描述符。

功能 4：CoremodGuard（Coremod 侧独立守卫）

· 效果：Coremod 在主模块之前加载，不能引用主模块的 DefenseHandler。需要在 Coremod 里独立实现“玩家是否持有锚点”的检查
· 实现建议：
  · 独立类 com.maha_fish.finaljudgment.coremod.CoremodGuard
  · 直接检查玩家背包里的锚点物品 NBT
  · 与主模块的 T0Anchor 逻辑并行，但代码独立
  · 关键：Coremod 与主模块不能直接引用；不要假设能看到同一个 Class 对象。

功能 5：KillRegistry（Coremod ↔ Java 层通信桥）

· 效果：Coremod 的 CoremodKillTicker.tick(entity) 每 tick 检查实体是否被标记
· 实现建议：
  · 主模块侧定义 KillRegistry 类，静态方法 isMarked(Object) / forceKill(Object)
  · Coremod 侧通过反射加载：用实体对象的 getClass().getClassLoader()（同一 ClassLoader）加载 KillRegistry
  · 反射结果缓存，避免每次 tick 都查找
  · 关键：
    · 参数用 Object 而非 Entity——Coremod 编译期不依赖 MC 类
    · 标记超时 30 秒自动清除，避免死循环
  · 修正：必须保证 KillRegistry 只被加载一份。Coremod jar 不要打包主 mod 的 KillRegistry 或 common 类，否则反射会加载到错误副本。

功能 6：CoremodKillTicker（tick 级击杀检查）

· 效果：由 KillTickerPatch 在 Entity.tick 入口注入的调用
· 实现建议：
  · Coremod 侧的类（不是主模块的）
  · 通过反射调用 KillRegistry.isMarked / forceKill
  · 绝不对 tick 抛异常

5.3 关键技术点

技术点 说明
描述符解析 运行时字节码字段名不确定，必须从 ClassNode 解析
纯字节码守卫 只比较字段 + RETURN / IRETURN，零方法调用
-transformer 后缀 ModLauncher 优先扫描
独立 jar 不能用 jarJar
onLoad 设系统属性 让主 mod 能查询 coremod 状态
反射跨 ClassLoader Coremod 加载主模块类必须用实体对象的 ClassLoader
Minecraft 仅客户端 服务端 Coremod 不能无条件注入 Minecraft.runTick
Forge 扩展方法 ServerLevel.removeEntityComplete 仅当 Forge 提供时注入

5.4 加强拓展

扩展方向 1：通用防守检测

· 目标：扫描所有第三方类，识别可疑防御类
· 实现：遍历类 → 检查继承链 + 覆写方法特征 → 标记为可疑
· 技术点：ASM 分析类结构，不需要硬编码任何 mod 名

扩展方向 2：目标类通用守卫

· 目标：不管目标类是哪个 mod 生成的，只要覆写了关键方法就注入守卫
· 实现：在 Coremod 阶段扫描所有类，对匹配特征的类做注入
· 技术点：ITransformer 的 targets() 支持通配符或模式匹配

5.5 自检清单

· □ 所有守卫都按返回类型生成了正确 return 吗？
· □ 有没有不小心调用 Java 方法？（搜 INVOKE 指令）
· □ 字段解析失败时，打印了目标类的所有字段吗？
· □ Minecraft.runTick 有没有分侧？
· □ 独立守卫类有没有 import 主模块的类？
· □ 它由哪个 ClassLoader 加载？
· □ ServerLevel.removeEntityComplete 找不到时是否安全跳过？

---

第六部分：Native 层

6.1 功能目标

提供 JVMTI 能力。唯一能绕过 Java ClassFileTransformer 链的层。

6.2 需要实现的功能

功能 0：JNI 生命周期

· 效果：JVM 加载 / 卸载 Native 库时初始化 / 清理全局资源
· 实现建议：
  · JNI_OnLoad(JavaVM, void)：保存 JavaVM 指针，初始化互斥锁
  · JNI_OnUnload(JavaVM, void)：释放所有全局引用、清理哈希表、销毁锁
  · 跨平台锁：Windows CRITICAL_SECTION
  · C 侧必须维护的全局变量：
    · JavaVM* g_jvm
    · jvmtiEnv* g_jvmti
    · 日志桥接全局引用（g_loggerClass / g_loggerMethod）
    · 守卫回调全局引用（g_guardClass / g_guardMethod）
    · 类保护表（g_protected[] / g_protected_count / g_protect_enabled）
    · 运行时字节码捕获现场（g_capture_bytes / g_capture_len / g_capture_target / g_capture_enabled）
    · 主动替换现场（g_hook_bytes / g_hook_bytes_len / g_hook_target_class）
    · 我方 retransform 标记（g_self_retransform_count，计数器而非布尔）
    · 血量存储链表头（g_health_head / g_health_count）
  · 类保护表容量上限：MAX_PROTECTED_CLASSES = 64

功能 1：JVMTI 环境获取

· 效果：从 JavaVM* 拿到 jvmtiEnv*
· 实现建议：缓存 jvmtiEnv*，避免每次 GetEnv
· 注意：GetEnv 失败时打日志并返回 NULL，不要盲目继续

功能 2：能力申请

· 效果：申请 can_redefine_classes / can_retransform_classes / can_generate_all_class_hook_events / can_get_bytecodes
· 实现建议：AddCapabilities 一次性申请所有能力
· 处理：返回 JVMTI_ERROR_NOT_AVAILABLE 时也算成功（部分能力不可用，不阻塞）
· 提供能力位掩码查询：把 can_* 拼成一个 int 返回给 Java 层

功能 3：ClassFileLoadHook 回调

· 效果：拦截所有类加载 / retransform，按规则处理字节码
· 实现建议：4 段逻辑
  1. 捕获段：捕获模式下保存字节，放行
  2. 保护段：非我方 retransform 且保护表命中且字节不一致 → 返回基线
  3. 替换段：目标匹配 → 返回新字节
  4. 默认：放行
· 关键：判断“是否我方 retransform”要用一个全局计数器
· 保护段决策表：
  · Java 回调返回新字节 → 用新字节替换
  · Java 回调返回 null（正常无篡改）→ 放行
  · Java 回调失败（异常 / JNI 分配失败 / 环境不可用）→ 保守返回基线
  · 回调线程可能未 attach → 先 GetEnv，失败再 AttachCurrentThread，用完 DetachCurrentThread

功能 4：RedefineClasses 执行

· 效果：Java 层能提交字节码替换
· 实现建议：
  · 用 jvmtiClassDefinition 结构体，不是 jclass[]
  · 只能改方法体：不能加 / 删方法或字段，不能改签名，不能改类名 / 父类 / 接口
  · 提交前必须先 RetransformClasses 触发 hook，从 hook 抓取当前字节码作为基线
· ⚠️ 关键警告：
  · jvmtiClassDefinition 提交的字节码必须包含完整的 StackMapTable
  · 错误 62 = JVMTI_ERROR_FAILS_VERIFICATION，就是缺帧导致
  · Java 侧生成字节码必须用 COMPUTE_FRAMES | COMPUTE_MAXS（不是只 COMPUTE_MAXS）
  · ClassReader 不能 SKIP_FRAMES，否则 FrameNode 丢失，COMPUTE_FRAMES 也重建不全
  · 不能对 net.minecraft.* 的 vanilla 类调用：重定义 LivingEntity 会影响所有实体，风险大。应在 Java 侧过滤
· 与 ForcedClassRewrite 的区别：ForcedClassRewrite 用 Instrumentation.retransformClasses，允许 vanilla；Native Redefine 用 JVMTI，禁止 vanilla。两者目标类不重叠。

功能 5：运行时字节码捕获

· 效果：拿到“当前 JVM 里的字节码”，与 jar 里的原始字节码不同
· 实现建议：
  · 设捕获标志 → RetransformClasses → hook 收到字节码并保存 → 返回
  · 不能用 GetBytecodes：它只读单个方法的字节码，读不到整类

功能 6：类保护表

· 效果：检测我方类被第三方篡改
· 实现建议：
  · 启动时采集基线
  · 每次 hook 触发时对比字节
  · 必须排除我方 retransform，否则自伤
  · 修正：基线按类来源选择。MC 类不能用 jar 原始字节作基线，必须用首次运行时捕获的字节码；自身类可用 jar 版本。
  · JNI 签名：NativeGuard.verifyAndRepair 的签名严格为 (Ljava/lang/String;[B)[B，C 侧 GetStaticMethodID 必须用这个签名

功能 7：直杀实体

· 效果：JNI 直接写 removalReason 字段，绕过所有 Java 方法
· 实现建议：GetFieldID + SetObjectField
· 技术点：字段名用双候选（SRG 名 + Mojang 名），失败时通过 ClassNode.fields 按描述符解析
· 完整流程：
  1. GetObjectClass(entity)
  2. 尝试 GetFieldID(entityCls, "removalReason", "...")
  3. 失败则尝试 GetFieldID(entityCls, "f_146795_", "...")
  4. 失败则返回 false
  5. 找 RemovalReason 类
  6. 调 values() 拿枚举数组
  7. 按 ordinal 取具体值
  8. SetObjectField(entity, fieldID, reason)

功能 8：血量 Native 存储

· 效果：血量值存 C 层，Java 堆扫描和反编译工具找不到
· 实现建议：C 层哈希表 / 链表，key 是 UUID 字符串
· 数据结构（三基线）：

```c
typedef struct HealthEntry {
    char* uuid;
    float health;      // 工作值
    float maxHealth;   // 上限值
    float baseline;    // 永久基线
    struct HealthEntry* next;
} HealthEntry;
```

· Java 侧便捷方法签名必须与 ASM invoke 严格一致：
  · getHealthForEntity(Lnet/minecraft/world/entity/LivingEntity;)F
  · setHealthForEntity(Lnet/minecraft/world/entity/LivingEntity;F)V
· 修正：Bootstrap ClassLoader 看不到 MC 类。若这些类的方法签名含 LivingEntity，不能放 Bootstrap。必须拆分“纯 native 声明”和“MC 类型包装”。
· 存储操作清单：set / get / setMax / getMax / has / remove / clearAll
· 未隔离实体回退：调用方先查 hasEntry(uuid)，没有则走原版 DATA_HEALTH_ID 读写

功能 9：日志桥接

· 效果：C 侧日志进 latest.log
· 实现建议：
  · C 侧用 fj_log 封装
  · 优先 JNI 回调 Java 的 nativeLog(String)
  · 失败回退 fprintf(stderr)
  · 技术点：线程可能未 attach，要先 AttachCurrentThread，用完 DetachCurrentThread
  · Java 侧签名：public static void nativeLog(String)，严格 (Ljava/lang/String;)V
  · 挂载时机：必须在任何 native 调用之前（JNI_OnLoad 之后、探针之前）
  · Java 侧约束：只能调 slf4j 打印，不做其他事（可能被任意线程调用）

功能 10：Bootstrap 注入

· 效果：让关键类走 Bootstrap ClassLoader，绕过 ModLauncher 隔离
· 实现建议：AddToBootstrapClassLoaderSearch(jarPath)
· 约束：Bootstrap 加载的类不能引用任何 mod 类或 MC 类。能引用 JDK 全部类（java.*、javax.*、jdk.*）。需要拆分纯 native 声明和 MC 类型包装。
· 用途：把 common 的纯常量类注入到 Bootstrap，让所有 ClassLoader 都能读到同一份
· 限制：一旦注入不可撤回（JVM 生命周期内）

功能 11：类枚举 / 常量池

· 效果：反制分析基础
· 实现建议：
  · GetLoadedClasses + GetClassSignature
  · 返回内部名（斜杠分隔）
  · 返回格式：String[]
  · GetConstantPool 返回原始字节数组（反汇编用）

功能 12：我方 retransform 标记

· 效果：Java 层告诉 Native 层“这次 retransform 是我方发起的”
· 实现建议：全局计数器，Java 层 nativeSetSelfRetransform(true/false) 控制
· 为什么用计数器而非布尔：多个线程同时 retransform 时，布尔标志会被覆盖。计数器用原子加 / 减，只要 > 0 就算我方 retransform。
· C 侧实现：

```c
static volatile int g_self_retransform_count = 0;

JNIEXPORT void JNICALL nativeSetSelfRetransform(JNIEnv* env, jclass cls, jboolean active) {
    if (active) {
        __sync_fetch_and_add(&g_self_retransform_count, 1);
    } else {
        __sync_fetch_and_sub(&g_self_retransform_count, 1);
    }
}

#define IS_SELF_RETRANSFORM() (g_self_retransform_count > 0)
```

· 用途：classFileLoadHook 收到重定义事件时判断来源

功能 13：Native 自检工具链

· 效果：启动时验证 JVMTI 能力是否可用
· 实现建议：
  · NativeTest.runSelfTest()：查询能力位掩码
  · NativeRetransformTest.run()：对一个测试类做一次 retransform，验证效果
  · NativeTestTarget：测试目标类（初始 getValue() 返回 1）
  · NativeTestBytecodeBuilder：用 ASM 生成“返回 42”的字节码
  · 生产环境只查询能力 + 打日志，不做实际替换（避免每个玩家启动都触发）

6.3 关键技术点

技术点 说明
JNI_OnLoad/OnUnload 生命周期必须完整，否则全局引用泄漏
4 段 hook 逻辑 捕获 / 保护 / 替换 / 放行，按优先级判断
jvmtiClassDefinition RedefineClasses 的正确调用方式
COMPUTE_FRAMES RedefineClasses 提交的字节码必须包含完整栈帧
运行时捕获 用 RetransformClasses 触发 hook，不能用 GetBytecodes
我方标记 用计数器而非布尔，避免多线程覆盖
日志桥接 JNI 回调 + stderr 兜底
跨平台锁 Windows CRITICAL_SECTION
签名严格匹配 JNI 方法签名必须与 Java 侧一致，否则 GetMethodID 失败
Bootstrap 限制 Bootstrap 类不能引用 MC / mod 类，可以引用任意 JDK 类

6.4 JVMTI 错误码速查

错误码 名称 常见原因 处理
62 JVMTI_ERROR_FAILS_VERIFICATION StackMapTable 缺失 检查 COMPUTE_FRAMES 是否启用、ClassReader 是否 SKIP_FRAMES
66 UNSUPPORTED_REDEFINITION_METHOD_ADDED 试图加方法 只改方法体，不加方法
67 UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED 试图改字段 只改方法体，不动字段
68 UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED 试图改继承 不改类名 / 父类 / 接口
99 JVMTI_ERROR_NULL_POINTER 空指针 检查传入参数
103 JVMTI_ERROR_ILLEGAL_ARGUMENT 非法参数 检查 jvmtiClassDefinition 字段

6.5 加强拓展

扩展方向 1：多平台 Native

· 目标：Windows / Linux / macOS 独立编译
· 实现：Gradle 按 os.name 打包对应 dll / so
· 本项目当前仅做 Windows x86_64

扩展方向 2：方法级指纹保护

· 目标：类保护只对比方法体，忽略 JVM 规范化导致的类级属性差异
· 实现：Java 层用 ASM 提取每个方法体的字节哈希，C 层对比哈希
· 技术点：
  · 忽略 LabelNode / LineNumberNode / FrameNode
  · LDC 常量序列化为字符串
  · InvokeDynamic 序列化 bsm + name + desc
  · 跳转标签用“首次出现 ID”代替绝对偏移（跨 JVM 一致）
  · SHA-256 哈希

扩展方向 3：对方类重写

· 目标：改第三方防御类的方法体，让它们的覆写作废
· 实现：扫描所有第三方类 → 按特征匹配 → 改方法体为 super 调用
· 技术点：
  · 特征 = 继承 MC 实体类 + 覆写了关键方法
  · 不硬编码任何 mod 名
  · 用 RedefineClasses 提交
  · 修正：改方法体后必须用 COMPUTE_FRAMES | COMPUTE_MAXS，不是只 COMPUTE_MAXS。仅 COMPUTE_MAXS 可能导致 VerifyError。

扩展方向 4：对方 native 反制

· 目标：对方也用 native 库时，识别并应对
· 实现：枚举已加载 native 库 → 分析符号表
· 技术点：Windows 用 EnumProcessModules

---

第七部分：主 mod 模块

7.1 入口层

功能 1：主入口

· 效果：Forge 能加载我们的 mod
· 实现建议：
  · @Mod 注解
  · 构造器里只做注册（物品 / 网络 / 事件监听）
  · 不写业务逻辑

功能 2：初始化调度

· 效果：commonSetup 阶段按顺序初始化各层
· 实现建议：
  · 每个 init() 独立 try-catch
  · 顺序：Native → Agent → Core → Attack/Defense → 三条路线 → Client

功能 3：状态查询

· 效果：玩家能查询各层是否生效
· 实现建议：
  · Native：NativeBridge.isLoaded()
  · Agent：AgentBridge.isReady()
  · Coremod：检查系统属性 fj.coremod.loaded
  · Bootstrap：检查 Bootstrap 常量类是否可从 Bootstrap ClassLoader 读取（Class.forName(name, false, null)）或检查系统属性
  · 显示在武器 tooltip 上
  · 日志前缀规范：主 mod 用 [FJ]，Native 用 [FJ-Native]，Coremod 用 [FJ-Coremod]，Agent 用 [FJ-Agent]，Bootstrap 用 [FJ-Bootstrap]

7.2 Agent 桥接层

功能 1：反射读 Instrumentation

· 效果：主 mod 能拿到 Agent 的 Instrumentation
· 实现建议：
  · Class.forName("...InstHolder", true, ClassLoader.getSystemClassLoader())
  · 缓存结果
  · 探测失败用 debug 级日志（不是错误）

功能 2：动态附加

· 效果：从主 jar 提取 agent jar 并附加
· 实现建议：
  · 提取到临时文件
  · 启动子 JVM
  · 读子 JVM 输出转发到日志
  · 最多等 10 秒
  · 修正：先检测 jdk.attach 是否可用。标准 JDK 17 默认包含，不需要 --add-modules。只有 jlink 裁剪过的 JRE 才缺失，此时提示使用完整 JDK 或回退 -javaagent。

功能 3：定时 retransform

· 效果：每 5 秒对核心类 retransform
· 实现建议：
  · 用 Instrumentation.retransformClasses
  · 前后调 nativeSetSelfRetransform
  · 修正：跨 ClassLoader 必须反射或走共享层。

7.3 Native 桥接层

功能 1：JNI 声明与加载

· 效果：Java 侧调用 C 函数
· 实现建议：
  · public static native 声明
  · System.load 加载提取的 dll / so
  · 平台检测：os.name + os.arch
  · 加载流程：
    1. 按平台拼出资源路径 /META-INF/native/<platform>/<libname>
    2. 从资源读出流
    3. 写到临时文件，deleteOnExit
    4. System.load(tmpFile)
    5. 调 nativeInitLogger 注册日志桥接
    6. 调 nativePing 探针
  · 本项目仅支持 Windows x86_64

功能 2：日志接收

· 效果：C 侧日志进 Java 日志
· 实现建议：
  · public static void nativeLog(String message) 方法
  · 签名 (String) → void，必须 public static
  · 只调用 slf4j 打印，不做其他事（可能被任意线程调用）
  · 加 try-catch，绝不让异常冒泡回 C 层

功能 3：类保护注册

· 效果：注册需要保护的类 + 基线
· 实现建议：
  · 修正：基线按类来源选择。
  · MC 类 / 经过 ModLauncher/Mixin 的类：必须用首次运行时捕获的字节码，不能用 jar 原始字节。
  · 自身类 com.maha_fish.finaljudgment.*：通常不经过 SRG 映射，jar 版本可作为基线，除非被其他 transformer 改过。
  · 传递格式：nativeRegisterProtectedClass(internalName, baselineBytes) → boolean
  · ⚠️ 时序约束：NativeGuard.install 必须在 Agent retransform 完成之后调用，否则会把 Agent 的正常修改当成篡改。见第八部分初始化顺序。

功能 4：方法级指纹

· 效果：检测类是否被改（用方法级对比避免误报）
· 实现建议：
  · ASM 解析类 → 提取每个方法的字节序列 → SHA-256
  · 序列化时忽略 LabelNode / LineNumberNode / FrameNode
  · 跳转标签用“首次出现 ID”代替绝对偏移
  · 与基线指纹对比
  · 比对规则：
    · 方法数不同 → 不一致
    · 每个方法的哈希全部相同 → 一致
    · 不一致时输出具体哪些方法不同（-方法名 / +方法名 / ~方法名）

功能 5：通用类还原

· 效果：按特征把第三方覆写的方法改成 super 调用
· 实现建议：
  · 特征：继承 LivingEntity / Entity + 覆写关键方法
  · 18 项方法名单（tick / getHealth / setHealth / isRemoved / hurt / die / kill / ...）
  · 每项双候选匹配（Mojang + SRG）
  · 用 INVOKESPECIAL 直调父类实现
  · 改写规则：
    · 清空原方法体
    · 按参数类型逐个加载参数（ALOAD 0 → 参数 1...）
    · INVOKESPECIAL 调父类同名同描述符方法
    · 按返回类型生成对应 RETURN

功能 6：直杀实体

· 效果：JNI 直写 removalReason
· 实现建议：调 nativeKillEntity(entity, reasonOrdinal)
· 注意：Entity.RemovalReason.KILLED 的 ordinal 是 0
· 枚举序号（1.20.1）：
  · 0 = KILLED
  · 1 = DISCARDED
  · 2 = UNLOADED_TO_CHUNK
  · 3 = UNLOADED_WITH_PLAYER
  · 4 = CHANGED_DIMENSION

功能 7：血量存储桥

· 效果：Java 侧读写 C 层血量
· 实现建议：
  · 7 个 native 方法：set / get / setMax / getMax / has / remove / clearAll
  · 便捷方法包装（如 getHealthForEntity(Object)）
  · 签名必须与 ASM invoke 一致
  · 修正：若签名含 MC 类，不能放 Bootstrap。拆分纯 native 声明和 MC 类型包装。
  · 便捷方法内部逻辑：
    · getHealthForEntity：先 hasEntry(uuid)，无则走原版 DATA_HEALTH_ID
    · setHealthForEntity：直接写入 uuid → value

功能 8：RedefineClasses 执行器

· 效果：改任何已加载类的方法体
· 实现建议：
  · 捕获运行时字节 → ASM 变换 → nativeRedefineClass
  · ⚠️ 用 COMPUTE_FRAMES | COMPUTE_MAXS（不是只 COMPUTE_MAXS）
  · ClassReader 不 SKIP_FRAMES
  · 重写 getCommonSuperClass 用 context classloader
  · Java 侧过滤 vanilla 类：if (className.startsWith("net.minecraft.")) throw skip;
  · getCommonSuperClass 实现要点：
    · 用 Thread.currentThread().getContextClassLoader()
    · 失败时返回 java/lang/Object
    · 不能抛异常

功能 9：第三方类分析

· 效果：枚举第三方类，找出可疑防御类
· 实现建议：
  · 过滤前缀：排除 java. / net.minecraft. / net.minecraftforge. / cpw.mods. / com.mojang. / 我们自己的
  · 每个类 ASM 解析，判断特征
  · 可疑度评分（示例）：
    · 方法名以 on 开头且指令 > 5 → +1
    · 有 tick / onTick 方法 → +2
    · 有 hurt / die → +3
    · 有 setHealth / getHealth → +3
    · 有 setRemoved / remove → +2
    · 有 isRemoved / isAlive → +2
  · 总分 >= 4 判为可疑

7.4 Core 层

7.4.1 内存工具

功能 1：Unsafe 访问

· 效果：拿到 sun.misc.Unsafe 和 MethodHandles.Lookup.IMPL_LOOKUP
· 实现建议：
  · 主路径：反射 sun.misc.Unsafe.theUnsafe 字段（jdk.unsupported 模块默认 open，不需要 --add-opens）
  · 备用路径：通过 IMPL_LOOKUP 读 theUnsafe
  · jdk.internal.misc.Unsafe 通过 IMPL_LOOKUP 拿
  · 不推荐 Unsafe.getUnsafe()：mod 类调用会抛 SecurityException
  · JVM 参数：标准路径不需要任何 --add-opens。只有改用 Field.setAccessible(true) 访问 java.base 私有成员时才需要。规范：禁止使用 setAccessible 访问 java.base，一律走 Unsafe 直写。

功能 2：字段直写

· 效果：绕过所有 Java 方法写字段
· 实现建议：
  · 缓存字段 offset
  · Unsafe.putObject(target, offset, value)
  · 不使用 setAccessible：objectFieldOffset(Field) 不需要可访问性
  · 字段名走双候选匹配
  · 字段定位策略：
    1. 按名字候选找 declaredField
    2. 找不到时按类型名后缀匹配（如 EndsWith("RemovalReason")）

功能 3：血量字段读写

· 效果：直接操作 SynchedEntityData 里的血量
· 实现建议：
  · 定位 DATA_HEALTH_ID（EntityDataAccessor<Float>）
  · 优先走 Mixin accessor（refmap 自动映射）
  · 兜底走反射
  · 定位策略：
    1. 按名字候选（DATA_HEALTH_ID / f_20915_）
    2. 遍历所有 static EntityDataAccessor，按序列化 ID 匹配（1.20.1 中是 9）
    3. 位置缓存，避免重复反射

功能 4：SynchedEntityData 内部直写

· 效果：写 DataItem.value 对象，绕过 set()
· 实现建议：
  · 走 Mixin accessor 拿 itemsById（Int2ObjectMap）
  · 从 map 里按 accessor.getId() 拿 DataItem
  · 走 Mixin accessor 写 DataItem.value

7.4.2 状态管理

功能 1：影子血量

· 效果：血量值存 Native 层，Java 堆扫描找不到
· 实现建议：
  · Native 优先：NativeHealthStore.isReady() 时值存 C 层
  · Java 兜底：native 不可用时用 ConcurrentHashMap
  · 每 tick 校验影子完整性
  · 三基线：工作值 + 上限值 + 永久基线（防篡改）
  · ⚠️ 隔离判定必须加 instanceof Player：MixinLivingEntityHealth 里的 if (FieldIsolation.isIsolated(self.getUUID())) 必须在前面加 self instanceof Player 判断，否则某个怪物 UUID 一旦进隔离表，它的 setHealth 全被劫持到 Native
  · 校验与修复流程（每 tick）：
    1. 隔离标志被清 → 重新隔离
    2. Native 条目丢失 → 重新隔离
    3. Java 影子值异常（NaN / 负数 / 超限）→ 从永久基线恢复

功能 2：武器锚点

· 效果：识别玩家是否持有武器
· 实现建议：
  · 物品 NBT 上标记：final_judgment_core = "v1"
  · hasAnchorInInventory 扫描所有槽位
  · 重写而非新增：已有锚点才重写，避免给所有物品误加
  · 两条重写路径：
    1. 武器自己：inventoryTick 里调 forceRestore
    2. 玩家侧：DefenseHandler.playerTickGuard 里遍历背包重写

7.4.3 类处理

功能 1：从 jar 读原始字节码

· 效果：拿到 jar 里的字节码（未经 transformer 处理）
· 实现建议：
  · 定位自己的 jar：getProtectionDomain().getCodeSource().getLocation()
  · 四级回退：jar 文件 → classes 目录 → union 路径 → classloader
  · 缓存空数组作为“已确认读不到”标记：合成类 / 动态生成类永远读不到，不缓存会导致每次加载都重试
  · union 协议处理：从 CodeSource.getLocation() 输出里提取 jar 名，在 mods/ 目录反查。格式随 Forge 版本变化，需按实际输出适配，不要硬编码 %23

功能 2：ASM 方法体改写

· 效果：Agent 层改 LivingEntity 方法体
· 实现建议：
  · 修正：若只改常量、无分支，可用 COMPUTE_MAXS；若插入分支、跳转、异常处理，必须用 COMPUTE_FRAMES | COMPUTE_MAXS。
  · 若用 COMPUTE_FRAMES，必须重写 getCommonSuperClass 用 context classloader，因为 Agent ClassLoader 找不到 MC 类
  · 入口守卫而非清空方法体：保留原有指令
  · 幂等性检查：ASM 解析后检查目标方法是否已注入过守卫，已注入则跳过

功能 3：主 Transformer

· 效果：Agent 层主 transformer
· 实现建议：
  · 只处理 LivingEntity（精确匹配）
  · 幂等性：已注入过就跳过（避免每 5 秒重复改）
  · 职责单一：不处理自身类保护
  · 处理的方法名候选（含 SRG）：getHealth / m_21223_ / func_110143_a；setHealth / m_21153_ / func_70606_j

功能 4：自身类保护

· 效果：检测我方类被改并还原
· 实现建议：
  · 前缀匹配 com/maha_fish/finaljudgment/
  · 跳过合成类（__ / $$Lambda$ / $Proxy / $Mixin）
  · 修正：基线按类来源选择。MC 类不能用 jar 原始字节作基线；自身类可用 jar 版本，但首次运行时捕获更稳。
  · 基线存储：Map<String, byte[]>，首次见到时记录
  · 不一致时行为：返回基线，打警告日志

7.4.4 实体管理

功能 1：6 处集合摘除

· 效果：从所有服务端索引移除实体
· 实现建议：
  · 6 处位置：section.storage / visibleEntityStorage / knownUuid / entityTickList.active / entityTickList.passive / ChunkMap.entityMap
  · 必须全摘：只摘一处，对方从其他处恢复
  · 字段名走描述符解析，不只靠双候选名
  · 返回值 0 不是失败：0 只是“没清到这一处”，不代表 KillChain 应该退出
  · 每处实现要点：
    · section.storage：拿 Long2ObjectMap，按 SectionPos.asLong 定位 section，拿 ClassInstanceMultiMap，从 Collection 里 remove
    · visibleEntityStorage：同上
    · knownUuid：从 Set<UUID> 里 remove
    · entityTickList.active / passive：从 Int2ObjectMap 里按 entityId remove
    · ChunkMap.entityMap：从 Int2ObjectMap 里按 entityId remove

功能 2：深度扫容器

· 效果：兜底——从对象图任意位置移除引用
· 实现建议：
  · 递归遍历（限深 5 层）
  · IdentityHashMap 去重
  · 跳过 JDK 核心类
  · 从所有 Map / Collection 移除目标
  · 匹配规则：
    · Map value == target 或 key == UUID 或 key == ID
    · Collection contains target 或 contains UUID 或 contains ID
  · 遍历时用 new ArrayList<>(map.values()) 快照，避免并发修改

功能 3：实体重注入

· 效果：持有者被移除时加回
· 实现建议：反射调 entityManager.addEntity
· 参数类型是 Object，不是 Entity（泛型擦除）

7.4.5 循环 / 时间

功能 1：服务端 tick 劫持

· 效果：每 tick 末尾执行我们的逻辑
· 实现建议：
  · TickEvent.ServerTickEvent
  · EventPriority.LOWEST + Phase.END：抢在对方 tick 重置字段之后
  · 每 tick 调 TrackingAttack.tick / DefenseHandler.serverTickMaintenance / SilenceKillUtil.tickGuard / EventBusGuard.tick / KillPressure.tick / HeartbeatThread.drainFixups
  · 注意：如果代码里写的是 HIGHEST + START，那在 Forge 事件顺序里是“最早执行”，会被对方的 tick 重置覆盖。应改为 LOWEST + END

功能 2：独立心跳线程

· 效果：主线程被劫持时的兜底
· 实现建议：
  · ScheduledExecutorService
  · 线程安全边界：只做原子字段写 + ConcurrentHashMap 操作
  · 非原子操作（改血量 / 查实体）打标记，让主线程 drainFixups 消费
  · pendingFixup 是 Map<UUID, Integer>，计数用 merge(uuid, 1, Integer::sum)
  · 心跳周期：50ms
  · 线程优先级：Thread.MAX_PRIORITY
  · 守护线程：true
  · 能做的操作：
    · 写 int / float / boolean 字段（原子）
    · 写对象引用（原子）
    · ConcurrentHashMap 读写
  · 绝不能做的操作：
    · 调任何 MC 方法
    · 遍历 ArrayList / HashMap
    · 操作实体列表 / 世界 / ServerLevel
  · 消费流程：主线程 LoopHijacker.onServerTick 里调 drainFixups，取每个 UUID 对应的 ServerPlayer，做非原子操作

7.4.6 隔离 / 审计

功能 1：隔离状态管理

· 效果：监听玩家 tick，切换隔离状态
· 实现建议：
  · PlayerTickEvent
  · 有锚点则 isolateHealthField
  · 无锚点则 releaseIsolation
  · 玩家登出时合并清理：releaseIsolation + 清锚点 + ServerAutoAttack.clear

功能 2：实体列表守护

· 效果：持有者被从实体列表移除时重注入
· 实现建议：每 tick 检查 level.getEntity(id)
· 重注入方式：
  1. SectionInjector.reattach（反射调 entityManager.addEntity）
  2. 降级：level.addFreshEntity

功能 3：反射审计

· 效果：启动时打印所有关键字段的真实运行时名字
· 实现建议：
  · 只在 -Dfj.reflection.audit=true 时启用
  · 调用点：if (Boolean.getBoolean("fj.reflection.audit")) ReflectionAudit.run();
  · 不要无条件调用（即使 ReflectionAudit.run() 内部会判断，外部调用也应加判断）
  · 打印每个类的所有字段名 + 类型
  · 审计目标类：
    · net.minecraft.world.entity.Entity
    · net.minecraft.world.entity.LivingEntity
    · net.minecraft.world.entity.Mob
    · net.minecraft.server.level.ServerLevel
    · net.minecraft.world.level.entity.PersistentEntitySectionManager
    · net.minecraft.world.entity.EntityTickList
    · net.minecraft.server.level.ServerChunkCache
    · net.minecraft.server.level.ChunkMap
    · net.minecraft.world.level.entity.EntitySectionStorage
  · 打印格式：[FJ-AUDIT] 类名.字段名 : 类型名 (修饰符)

7.5 Attack 层

功能 1：攻击入口

· 效果：接收来自事件和网络包的攻击请求
· 实现建议：
  · 左右键独立冷却（lastMeleeTime / lastRangeTime / lastRemoteTime）
  · 冷却 50ms
  · 检查锚点
  · 公开入口（参数类型明确）：

```java
void onMeleeHit(ServerPlayer attacker, LivingEntity target)
void onRangeAttack(ServerPlayer attacker)
void onRemoteAttack(ServerPlayer attacker)
void onMeleeHitDirect(ServerPlayer attacker, LivingEntity target)  // ServerAutoAttack 调，不检查冷却
```

功能 2：事件触发

· 效果：左键 / 右键触发攻击
· 实现建议：
  · 4 个事件：RightClickItem / RightClickBlock / EntityInteract / LeftClickBlock
  · HIGHEST + receiveCanceled = true
  · 不用 isCanceled 检查：其他模组取消后我们仍然处理
  · 各事件的行为：
    · RightClickItem：AABB 范围扫描 + swing + setCanceled
    · RightClickBlock：范围扫描 + swing + setUseBlock(DENY) + setUseItem(DENY) + setCanceled
    · EntityInteract：范围扫描 + swing + setCanceled
    · LeftClickBlock：射线扫描 + 命中则 onMeleeHit + swing

功能 3：位置判定

· 效果：不用 AABB 找目标
· 实现建议：
  · 从 ServerLevel.getAllEntities 枚举
  · 用 entity.position() 而非 getBoundingBox()
  · 视线夹角判定（cos > 0.80）
  · 绕过对方对 getBoundingBox / isPickable 的覆写
  · 常量：
    · MELEE_REACH = 6.0
    · MAX_COS_ANGLE = 0.80
    · POSITION_TOLERANCE = 1.5
    · RANGE_RADIUS = 8.0
    · REMOTE_DISTANCE = 32.0
  · 命中判定流程：
    1. 从 ServerLevel.getAllEntities() 枚举
    2. 距离过滤：eyePos.distanceTo(living.position()) <= maxDistance + 1.5
    3. 视线夹角：toTarget.normalize().dot(lookDir) >= 0.80
    4. 取最近的一个

功能 4：击杀链

· 效果：多条独立击杀路径，任一条成功即停
· 实现建议：
  · 路径分 6 段，顺序：
    1. 改逻辑（Native 改对方方法体）
    2. 改状态（集合摘除）
    3. 写字段（Native / Unsafe / Mixin）
    4. 反射移除
    5. 弱 Java 兜底
    6. 借用（调用对方 writer / provider）
  · 每条路径独立 try-catch
  · 每条后调 KillValidator.isGone 校验
  · 完整 16 条路径（按实际强度降序）：
    1. NativeRedefineAll：JVMTI 改第三方 boss 类方法体
    2. NativeKill：JNI 直写 removalReason
    3. UnsafeWrite：Unsafe.putObject
    4. MixinAccessorWrite：Mixin accessor 走 refmap
    5. StorageExtract：6 处集合摘除
    6. MapSweep：递归扫容器
    7. DirectChunkMapRemove：直接操作 ChunkMap.entityMap
    8. EntityManagerForceRemove：反射调 entityManager.remove
    9. MixinInvokerCall：Mixin invoker 调 setRemoved
    10. VanillaSetRemoved：原版 setRemoved
    11. VanillaRemove：原版 remove
    12. VanillaDiscard：原版 discard
    13. ZeroHealthSetRemoved：血量归零 + setRemoved
    14. ZeroHealthDie：血量归零 + die
    15. MutatorBorrow：反射调对方 setter
    16. ProviderBorrow：反射调对方 getter
  · 前置准备：掉落生成 / 图腾清空 / 血量归零 / 死亡包注入 / 注册守护 / 写 KillFlag.targetEntity
  · 对抗环境特殊规则：不“见好就收”，全部路径跑一遍

功能 5：验证

· 效果：判定实体是否“事实消失”
· 实现建议：
  · 6 维位掩码：isRemoved / removalReason / level.getEntity / section / tickList / chunkMap
  · 修正：判定顺序如下，不得只凭单一字段：
    1. 若 removalReason != null 且 isRemoved == true → 消失
    2. 若 level.getEntity(id) == null → 消失
    3. 否则未消失
  · 前 3 维全部清除才算消失
  · 不要只看后 3 维：反射可能失败
  · 位掩码定义：
    · IN_ISREMOVED = 1
    · IN_REMOVALFIELD = 2
    · IN_LEVEL = 4
    · IN_SECTION = 8
    · IN_TICKLIST = 16
    · IN_CHUNKMAP = 32
    · ALL_PRESENT = 63

功能 6：压力窗口

· 效果：单次失败后每 tick 重压
· 实现建议：
  · 激活后每 tick 重跑路径
  · 连续 5 tick 稳定 → 成功
  · 60 tick 超时放弃
  · 每 tick 重改逻辑（patchEntityClass）——对抗对方 native 覆盖
  · 状态机：
    · activate() → ACTIVE
    · ACTIVE 每 tick：
      · isGone() → stableTicks++
      · stableTicks >= 5 → DONE（成功）
      · 否则 → rePush()，pushedTicks++
      · pushedTicks >= 60 → GIVE_UP
  · rePush 操作：NativeKill / UnsafeWrite / StorageExtract / MixinInvokerCall

功能 7：击杀工具

· 效果：掉落 / 图腾 / 血量归零 + 独立 tick 守护
· 实现建议：
  · 委托 KillChain
  · 独立守护列表（silencedTargets），每 tick 补刀
  · 守护逻辑：
    · level.getEntity(id) == null → 清守护
    · raw health > 0 → 重写 0
    · removalReason == null → 重写 KILLED

功能 8：服务端主动扫描

· 效果：网络包 + 心跳双通道
· 实现建议：
  · 客户端每 2 tick 发 AttackRequestPacket
  · 服务端心跳超时 10 tick
  · 每 3 tick 主动扫描视线内目标
  · 心跳机制：
    · 收到 AttackRequestPacket 或 AttackStatePacket(true) 时刷新 lastHeartbeatTick
    · 每次 tick 检查 currentTick - lastHeartbeatTick > 10 → 停止

功能 9：客户端死亡注入

· 效果：让玩家目标客户端死亡但服务端存活
· 实现建议：
  · 发 ClientboundPlayerCombatKillPacket
  · 配套：拦 ServerboundClientCommandPacket.PERFORM_RESPAWN

功能 10：追踪攻击

· 效果：对方每 tick 瞬移时仍能命中
· 实现建议：连续 5 tick 重定位 + 攻击
· 追踪表：Map<UUID, TrackedTarget>，每 tick 递减 remainingTicks

7.6 Defense 层

功能 1：伤害拦截

· 效果：持有者免疫所有伤害
· 实现建议：
  · LivingHurtEvent / LivingDeathEvent
  · HIGHEST 优先级
  · 反制哨兵：被攻击 3 次触发 fallbackKill 反击
  · 反制哨兵参数：
    · COUNTER_ATTACK_THRESHOLD = 3
    · COUNTER_ATTACK_WINDOW_MS = 5000

功能 2：监听器守护

· 效果：我方监听器被移除时重注册
· 实现建议：
  · 只保护有 @SubscribeEvent 的类：避免对无监听器的类反复注册（死循环）
  · 注册后复查：如果还不在 EventBus 里，加入黑名单
  · 警告限流 60 秒：避免每 tick 刷屏
  · 保护列表（6 个）：
    1. DefenseHandler
    2. LoopHijacker
    3. EntityListGuard
    4. AttackEventHandler
    5. ServerAutoAttack
    6. IsolationManager

功能 3：EventBus 结构锚点

· 效果：检测 EventBus 内部引用被替换
· 实现建议：
  · 保存 listeners 字段引用
  · 每 tick 检查引用是否被替换
  · 不做内容对比（内容变化是合法的）

功能 4：背包快照

· 效果：背包被强制清空时恢复
· 实现建议：每 1 秒快照一次，异常清空则恢复
· 快照范围：主背包 36 格（不含副手 / 盔甲槽）
· 恢复条件：所有槽位全部为空

7.7 Client 层

功能 1：左键检测

· 效果：客户端检测左键，主动发网络包
· 实现建议：
  · Mixin 挂 Minecraft.runTick：绕过 Forge 事件，对方无法取消
  · 状态机：上升沿 / 持续按住 / 下降沿
  · getGameTime() 去重（每帧调一次，只在 gameTime 变化时处理）
  · 状态机参数：
    · REPEAT_INTERVAL = 2
    · REPEAT_TIMEOUT = 40
  · 各状态行为：
    · 上升沿：sendAttackRequest + sendAttackState(true)
    · 持续按住：每 REPEAT_INTERVAL 发一次 request（超过 REPEAT_TIMEOUT 后降为 10）
    · 下降沿：sendAttackState(false)

功能 2：渲染保护

· 效果：防止对方通过渲染路径搞崩我们
· 实现建议：
  · 监听渲染事件
  · 持有者渲染取消 false（不拦）
  · 持有者 HUD 渲染取消 false（不拦）

7.8 Mixin 层

功能 0：Mixin 配置

· 效果：Mixin 框架能加载注入
· 必须文件：finaljudgment.mixins.json（放在 resources/ 下）
· 最小模板：

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.maha_fish.finaljudgment.mixin",
  "compatibilityLevel": "JAVA_17",
  "refmap": "finaljudgment.refmap.json",
  "mixins": [
    "MixinEntityAccessor",
    "MixinEntityInvoker",
    "MixinEntityInvulnerable",
    "MixinLivingEntity",
    "MixinLivingEntityDead",
    "MixinLivingEntityDeadAccessor",
    "MixinLivingEntityDie",
    "MixinLivingEntityDrop",
    "MixinLivingEntityHealth",
    "MixinPlayer",
    "MixinPlayerDrop",
    "MixinServerLevel",
    "MixinServerPlayerDie",
    "MixinServerGamePacketListener",
    "MixinSynchedEntityDataAccessor",
    "MixinDataItemAccessor"
  ],
  "client": [
    "MixinClientPacketListener",
    "MixinMinecraftAttackTrigger",
    "MixinMinecraftScreen",
    "MixinRenderProtector"
  ],
  "injectors": { "defaultRequire": 1 }
}
```

· build.gradle 里必须配置：

```groovy
mixin {
    add sourceSets.main, 'finaljudgment.refmap.json'
    config 'finaljudgment.mixins.json'
}
```

· injectors.defaultRequire = 1 的含义：注入失败时抛异常，不静默

功能 1：Accessor / Invoker

· 效果：直接读写 private 字段 / 调 private 方法
· 实现建议：
  · @Accessor / @Invoker + refmap
  · 不走反射：refmap 自动处理 SRG 映射
  · 用于：Entity.removalReason / Entity.setRemoved / LivingEntity.dead / SynchedEntityData.itemsById / DataItem.value

功能 2：方法注入

· 效果：在方法 HEAD 处拦截
· 实现建议：
  · 关键注入点：
    · LivingEntity.getHealth / setHealth / getMaxHealth：走影子血量
    · LivingEntity.die / dropAllDeathLoot：阻止
    · Entity.isInvulnerableTo / setRemoved / discard：无敌
    · Player.tick / hurt / getHealth / setHealth：关键——Player 覆写了这些方法，必须单独注入
    · ServerPlayer.die：单独拦（有额外逻辑）
    · ServerLevel.tickNonPassenger
    · ServerGamePacketListener.handleClientCommand：拦重生
    · ClientPacketListener.handlePlayerCombatKill：拦死亡屏
    · Minecraft.runTick：客户端触发（仅客户端）
    · Minecraft.setScreen：兜底
  · 所有注入方法加 @SuppressWarnings("mapping") 静默“映射未验证”警告

功能 2 补充：完整描述符表

目标类 方法 参数描述符
LivingEntity getHealth ()F
LivingEntity setHealth (F)V
LivingEntity getMaxHealth ()F
LivingEntity die (Lnet/minecraft/world/damagesource/DamageSource;)V
LivingEntity hurt (Lnet/minecraft/world/damagesource/DamageSource;F)Z
LivingEntity isDeadOrDying ()Z
LivingEntity dropAllDeathLoot (Lnet/minecraft/world/damagesource/DamageSource;)V
Entity isInvulnerableTo (Lnet/minecraft/world/damagesource/DamageSource;)Z
Entity isInvulnerable ()Z
Entity fireImmune ()Z
Entity setRemoved (Lnet/minecraft/world/entity/Entity$RemovalReason;)V
Entity discard ()V
Player tick ()V
Player hurt (Lnet/minecraft/world/damagesource/DamageSource;F)Z
Player dropEquipment ()V
ServerPlayer die (Lnet/minecraft/world/damagesource/DamageSource;)V
ServerLevel tickNonPassenger (Lnet/minecraft/world/entity/Entity;)V
ServerGamePacketListener handleClientCommand (Lnet/minecraft/network/protocol/game/ServerboundClientCommandPacket;)V
ClientPacketListener handlePlayerCombatKill (Lnet/minecraft/network/protocol/game/ClientboundPlayerCombatKillPacket;)V
Minecraft runTick (Z)V
Minecraft setScreen (Lnet/minecraft/client/gui/screens/Screen;)V
PlayerRenderer render (Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V

7.9 加强拓展

扩展 1：多平台 Native

· 目标：Windows / Linux / macOS 独立编译
· 实现：Gradle 按 os.name 打包对应 dll / so

扩展 2：反射字段审计工具

· 目标：生产环境出问题时能快速定位字段名错误
· 实现：-Dfj.reflection.audit=true 时启动 dump

扩展 3：四层状态可视化

· 目标：玩家能看到哪层生效
· 实现：武器 tooltip 显示 Native / Agent / Coremod / Bootstrap 四层状态

扩展 4：通用防御类还原

· 目标：还原所有第三方覆写的方法
· 实现：特征匹配 + ASM 改方法体

扩展 5：对抗 native 反制

· 目标：识别并应对对方 native 库
· 实现：枚举 native 库 + 分析符号表

扩展 6：动态 API 适配

· 目标：Forge 版本变化时 SRG 名自动更新
· 实现：从 MCPConfig / joined.tsrg 读取映射

---

扩展 7：我方攻击手段（学习对方）

目标：把对方用过的攻击手法变成我们的武器，确保目标实体事实消失，无论对方如何防御。

7.9.7.1 方法层攻击

手段 实现 落点
AllReturn 对目标实体所属模组包所有 boolean/void 方法强制返回 true/void Coremod、Native
方法体清空 第三方防御类方法体清空为直接 RETURN Native RedefineClasses
INVOKESPECIAL 直调父类 第三方覆写方法改成直接调父类 Native RedefineClasses
方法头插桩 对方关键方法 HEAD 处插入 if (条件) return 默认值 Coremod、Agent
异常注入 对方关键方法注入 ATHROW，让调用方异常降级 Native RedefineClasses
常量返回替换 把 getHealth() 等方法体改成 return 常数 Native RedefineClasses

7.9.7.2 事件层攻击

手段 实现 落点
清空 EventBus listeners 反射拿 listeners，直接 clear() Agent、Coremod
替换 listeners 引用 listeners 指向新的空 Map/List Agent、Coremod
摘除对方监听器 按类名/实例匹配精准移除 Agent
注册高优先级监听器 HIGHEST + receiveCanceled 抢先取消事件 事件层
伪造 TickEvent 顺序 抢占 LOWEST + END，让对方末尾逻辑不执行 事件层

7.9.7.3 数据层攻击

手段 实现 落点
EntityDataSweep 遍历 itemsById，float/int 改成 0 或负值 Core、KillChain
NBT 数值清洗 遍历 NBT，Health/Absorption 改成 0 Core、KillChain
反射改血 定位 DATA_HEALTH_ID，直接写 DataItem.value Core、KillChain
Unsafe 字段直写 缓存 offset，putObject/putFloat/putInt Core、KillChain
VarHandle 改字段 MethodHandles.Lookup 获取句柄 Core（备用）
修改 final 字段 Unsafe 强行写 final Core（备用）
清空实体集合 反射拿 entityTickList、ChunkMap.entityMap，直接 remove Core、KillChain
替换静态字段 改对方关键静态字段引用 Core、KillChain
深度扫容器 递归遍历对象图，移除目标引用 Core、MapSweep

7.9.7.4 类加载与字节码攻击

手段 实现 落点
加密字节码 运行时解密加载，静态分析看不到 Native、Agent
动态生成类 运行时 defineClass 生成防御类 Native、Agent
隐藏类 Lookup.defineHiddenClass 承载核心逻辑 Native
动态代理 Proxy.newProxyInstance 生成 Entity 子类 Core
LambdaMetafactory 生成方法句柄，隐藏调用链 Core
ClassFileLoadHook 抢占 注册 JVMTI hook 优先返回自己的字节码 Native
Instrumentation 反复覆盖 定时 retransform，覆盖对方注入 Agent
Mixin 后撤销 检测到 Mixin 后 retransform 还原 Agent ForcedClassRewrite
JVMTI 能力竞争 申请 can_redefine_classes，让对方不可用 Native

7.9.7.5 击杀链 16 条路径（按强度降序）

```text
NativeRedefineAll  → JVMTI 改第三方 boss 类方法体
NativeKill         → JNI 直写 removalReason
UnsafeWrite        → Unsafe.putObject 写 removalReason
MixinAccessorWrite → Mixin accessor 走 refmap
StorageExtract     → 6 处集合摘除
MapSweep           → 递归扫容器移除目标
DirectChunkMapRemove → 直接操作 ChunkMap.entityMap
EntityManagerForceRemove → 反射调 entityManager.remove
MixinInvokerCall   → Mixin invoker 调 setRemoved
VanillaSetRemoved  → 原版 setRemoved
VanillaRemove      → 原版 remove
VanillaDiscard     → 原版 discard
ZeroHealthSetRemoved → 血量归零 + setRemoved
ZeroHealthDie      → 血量归零 + die
MutatorBorrow      → 反射调对方 setter
ProviderBorrow     → 反射调对方 getter
```

7.9.7.6 针对性攻击

手段 实现 落点
改对方类字节码 ASM/JVMTI 改对方防御类 Native、Agent
替换对方静态常量 改对方 Constants Core
摘对方监听器 精准移除对方 DefenseHandler Agent
攻击对方 Native 库 枚举 native 库 + 分析符号表 Native
攻击对方 Agent 检测子 JVM 附加，阻止 jdk.attach Agent
攻击对方 Coremod 抢占加载顺序，覆盖 transformer Coremod
欺骗对方 KillValidator 让 isRemoved 返回 TRUE 但实体存活 Deception 层
伪造对方心跳 模拟 AttackRequestPacket 网络层
篡改对方时间 改 gameTime、tickCount Core
内存扫描 扫描 Java 堆找对方关键字段 Core

---

扩展 8：我方防御手段

目标：持有者不被反制，核心状态不被篡改，关键路径不被中断。

8.1 多层纵深与失败隔离

· 事件 + Mixin + Coremod + Agent + Native + Bootstrap 六层并存
· 每层职责单一，同一方法只由一层负责
· 每条路径独立 try-catch，失败隔离
· 每层独立日志前缀

8.2 反方法返回值欺骗

· KillValidator 字段级校验：不依赖 isRemoved()，直接读 removalReason
· 6 维位掩码 + 3 步判定
· 多来源交叉验证：level.getEntity + entityTickList + ChunkMap.entityMap + section.storage
· 物理存在性检查

8.3 反事件层攻击

· EventBusGuard 保护 6 个类 + 自己加入保护列表
· Agent 定时反射重注册：每 5 秒通过反射调用，不依赖 LoopHijacker
· EventBusAnchor 检查引用替换 + 内容清空
· 非事件总线自愈路径

8.4 反类加载与字节码攻击

· NativeGuard 类保护表：MAX_PROTECTED_CLASSES = 64
· 双基线校验：jar 原始字节 + 首次运行时捕获字节
· 方法级指纹：ASM 提取方法体 SHA-256
· NativeGuard.install 时序：在 Agent retransform 和 ForcedClassRewrite 之后
· ForcedClassRewrite：Mixin 之后用 Instrumentation.retransformClasses
· Native RedefineClasses：JVMTI 改第三方类，禁止 vanilla
· 运行时字节码捕获：RetransformClasses 触发 hook
· 多时机捕获加密字节码
· 动态生成类检测：ClassFileLoadHook 记录
· 隐藏类检测：JVMTI 能力枚举 + ClassFileLoadHook
· 行为分析替代静态分析
· 第三方类可疑度评分

8.5 反数据层攻击

· 血量 Native 优先
· 影子血量三基线
· Mixin accessor 优先
· EntityData 直写绕过 set()
· Unsafe 直写字段
· 禁止 setAccessible 访问 java.base
· 字段名集中管理
· 描述符解析四级回退
· FieldIsolation 加 instanceof Player

8.6 反 JVMTI / Agent 攻击

· 能力位掩码查询
· JVMTI 能力竞争降级
· 检测 jdk.attach
· 回退 -javaagent
· 子 JVM 附加
· 轮询等待 Instrumentation
· 我方 retransform 计数器

8.7 反监听器摘除与反缴械

· EventBusGuard 6 个保护类
· 注册后复查 + 黑名单
· 警告限流 60 秒
· 防缴械允许丢弃表
· 锚点槽位追踪表
· setItem 拦截双重确认
· 命令拦截

8.8 反内存扫描与反分析

· Native 存储关键值
· Bootstrap 共享常量
· KillFlag 唯一加载
· 日志前缀规范
· 反射审计只在开关开启时运行
· 混淆与加密自身关键类

---

扩展 9：隐藏关键字段（Deception 层）

目标：让对方找不到持有者的关键字段，攻击打在诱饵上。

核心原则：

· 真实值只存 Native，Java 堆里没有
· Java 层所有可见字段都是诱饵
· 自己人读取时从 Native 取真值
· 对方读取时拿到诱饵

隐藏字段优先级：

1. 血量（DATA_HEALTH_ID）
2. removalReason
3. isRemoved
4. NBT Health / Absorption
5. UUID / EntityID 映射

落点：

· Mixin：SynchedEntityData、Entity.isRemoved、Entity.removalReason、LivingEntity.getHealth、EntityNBT
· Coremod：纯字节码守卫，按调用者返回诱饵
· Native：HealthEntry 扩展 realRemoval / realIsRemoved
· NBT：CompoundTag 视图污染

调用者区分：

· ClassLoader 判断 + ThreadLocal 标记
· 自己代码调用前设 DeceptionContext.enter()
· Mixin 注入点检查 DeceptionContext.isSelf()
· 是 → 返回真值；否 → 返回诱饵

KillValidator 适配：

· 从 Native 读真值，不依赖 Java 层方法返回值

---

扩展 10：欺骗进攻成功

目标：让对方以为进攻成功，主动收手。

实现：

· 假死亡状态：isRemoved 返回 true，removalReason 返回 KILLED，getHealth 返回 0
· 假掉落 / 假死亡动画
· 假实体移除：level.getEntity(id) 返回 null
· 假击杀反馈：kill() / die() 返回成功，但不产生真实效果
· 假血量归零：SynchedEntityData 血量改成 0
· 假图腾触发：伪造“图腾已消耗”

落点：

· Mixin 方法返回值伪造
· Coremod 字段读取伪造
· Native 真实状态存储

---

扩展 11：让对方无法选中持有者

目标：从目标选择阶段就排除持有者。

实现：

· 从 AABB / 射线检测中排除：getBoundingBox / isPickable 返回空
· 从 level.getEntities / getAllEntities 中隐藏
· 从 entityTickList / ChunkMap.entityMap 中隔离，做“影子条目”
· UUID / 名字映射隔离
· 视线 / 距离判定污染：distanceTo / distanceToSqr / hasLineOfSight
· 目标选择事件拦截
· 网络层隐藏：定向发包过滤

落点：

· Mixin：isPickable、getBoundingBox、distanceTo、getEntities
· Coremod：实体列表过滤、UUID 映射
· 事件层：目标选择事件拦截
· 网络层：SelectivePacketFilter
· Native：集合摘除 + 影子条目

---

扩展 12：反传送机制

目标：对方瞬移时仍能命中，持有者被传送/移除时能恢复。

实现：

· 位置校验：deltaMovement、chunkPosition、feetBlockState
· 传送后恢复：setPosRaw、setBoundingBox、makeBoundingBox
· 传送后重新瞄准：calculateViewVector
· 传送后重新追踪：ChunkMap.updatePlayerStatus、TrackedEntity.serverEntity、seenBy、ServerEntity.entity
· 传送后查找：EntityLookup.byUuid、byId
· 追踪攻击连续 5 tick 重定位

落点：

· TrackingAttack
· EntityListGuard
· Deception 层
· Native 集合操作

---

扩展 13：动态生成类与隐藏类对抗

目标：识别、捕获、分析运行时动态生成的类。

实现：

· ClassFileLoadHook 记录所有类加载事件
· 定期 GetLoadedClasses 枚举
· 行为分析替代静态分析
· 对隐藏类尝试 JVMTI 能力枚举
· 动态类注入由 Agent 定时 retransform 扫描

落点：

· Native 层：ClassFileLoadHook、GetLoadedClasses、行为分析
· Agent 层：定时 retransform 扫描
· Coremod 层：通配符 targets

扩展 14：ECA 数据流改血反制

目标：反制三阶段递进改血。

实现：

· KillValidator 不依赖 getHealth() 返回值，直接读 Native 真实值或 removalReason
· 真实血量完全不在 Java 堆，Java 层只放诱饵
· NativeGuard 检测 getHealth() 被改并还原
· Coremod 守卫纯字节码比较，不调用 Java 方法
· 影子血量三基线 + 每 tick 校验

落点：

· Deception 层
· Native 层
· Coremod 守卫
· KillValidator

扩展 15：分层降级与回退路径

核心原则：Native 是首选，不是唯一。

分层降级总表：

能力 首选 回退 1 回退 2 回退 3
直杀实体 NativeKill UnsafeWrite MixinAccessorWrite VanillaSetRemoved
改方法体 NativeRedefineAll Agent ASM Coremod 守卫 Mixin 注入
集合摘除 Native 集合操作 Unsafe 读字段 + remove 反射读字段 + remove MapSweep
血量存储 Native HealthEntry Java 加密 Map 内存混淆 Map 原版 DATA_HEALTH_ID
类保护 NativeGuard Agent SelfClassProtector Coremod 守卫 方法级指纹
我方标记 Native 计数器 Bootstrap AtomicInteger 系统属性 ThreadLocal
日志桥接 Native JNI 回调 Bootstrap 共享日志类 系统属性 直接 Java 日志
Deception 真值 Native HealthEntry Java 加密 Map 内存混淆 不做 Deception
动态类检测 Native ClassFileLoadHook Agent ClassFileTransformer Coremod 通配符 行为分析
类枚举 Native GetLoadedClasses Instrumentation.getAllLoadedClasses ClassLoader 递归 不做枚举
字节码捕获 Native RetransformClasses Instrumentation.retransformClasses Agent ClassFileTransformer 从 jar 读原始字节

关键路径降级链：

直杀实体：
NativeKill → UnsafeWrite → MixinAccessorWrite → VanillaSetRemoved → StorageExtract → MapSweep → 压力窗口

改方法体：
NativeRedefineAll → Agent ASM → Coremod 守卫 → Mixin 注入 → ForcedClassRewrite

血量存储：
Native HealthEntry → Java 加密 Map → 内存混淆 → 原版 DATA_HEALTH_ID

类保护：
NativeGuard → Agent SelfClassProtector → Coremod 守卫 → 方法级指纹

我方 retransform 标记：
Native 计数器 → Bootstrap AtomicInteger → 系统属性 → ThreadLocal

Deception 真值存储：
Native HealthEntry → Java 加密 Map → 内存混淆 → 不做 Deception

每层独立启动检查：

· NativeBridge.load()：平台检测 → 提取 DLL → System.load → nativePing，任一步失败 nativeReady = false，打 WARN，不抛异常
· AgentBridge.getInstrumentation()：反射读 InstHolder，失败 agentReady = false，debug 日志
· Coremod 检测：检查系统属性 fj.coremod.loaded，失败 coremodReady = false
· Bootstrap 注入：Native 不可用则跳过，bootstrapReady = false

状态可视化：
武器 tooltip 显示四层状态，Native 不可用时显示 §c✗

扩展 16：降级路径的验证

每个 Unit 结束时验证：

1. 关掉 Native：Agent + Coremod + Mixin 仍能完成秒杀
2. 关掉 Agent：Native + Coremod + Mixin 仍能完成秒杀
3. 关掉 Coremod：Native + Agent + Mixin 仍能完成秒杀
4. 关掉 Mixin：Native + Agent + Coremod 仍能完成秒杀
5. 全部关掉：至少事件层 + Unsafe 能完成部分功能

---

7.10 武器物品实现

物品基础参数

· 继承剑类（SwordItem）
· 基础攻击伤害 +3，攻击速度修正 -2.4
· 不可堆叠（stacksTo(1)）
· 防火（fireResistant）

hurtEnemy 行为

· 只处理服务端玩家（ServerPlayer）
· 客户端攻击走网络包
· 调用攻击处理器
· 判断方式：if (attacker instanceof ServerPlayer)；或 if (!level.isClientSide)；推荐前者

use() 行为

· 返回 InteractionResultHolder.pass()
· 由事件层处理右键
· 不能返回 success 或其他值，否则客户端会尝试执行使用动作

inventoryTick 行为

· 服务端侧每 tick 调锚点重写（T0Anchor.forceRestore）
· 客户端侧不处理

appendHoverText 行为

· 显示描述文本
· 显示四层状态（Native / Agent / Coremod / Bootstrap 各一行，用 ✓ / ✗ 标记）
· 四层状态格式示例：§7Native: §a✓ §7| §7Agent: §a✓ §7| §7Coremod: §a✓ §7| §7Bootstrap: §a✓

isFoil

· 返回 true（发光）

自定义 Tier

· 耐久 Integer.MAX_VALUE
· 速度 12.0f
· 攻击加成 10.0f
· 等级 5
· 附魔价值 30
· 修复材料下界合金

自检

· □ 武器 use() 是否返回 pass()？
· □ hurtEnemy 是否只处理 ServerPlayer？
· □ inventoryTick 是否只在服务端重写锚点？
· □ appendHoverText 是否显示四层状态？

7.11 网络层

协议版本

· 字符串 "1"

通道名

· new ResourceLocation(modid, "main")

包分配

ID 包 Payload
0 攻击请求 无
1 攻击状态 boolean

攻击请求包

· 客户端发送频率：每 2 tick
· 服务端处理：刷新心跳 → 检查锚点 → 射线检测 → 触发近战
· 服务端处理顺序：
  1. ServerAutoAttack.refreshHeartbeat(player)
  2. AttackHandler.isHoldingJudgment(player) 检查
  3. RayTraceUtil.pickEntity(player, MELEE_REACH)
  4. AttackHandler.onMeleeHit(player, target)

攻击状态包

· 客户端在上升沿 / 下降沿发送
· 服务端处理：ServerAutoAttack.setAttacking(player, state)
· state = true 时刷心跳；state = false 时清状态

心跳超时

· 10 tick
· 每次收到攻击请求包或状态包时刷新
· 超过 10 tick 未刷新 → 自动停止攻击

网络通道注册时机

· 必须在构造器阶段注册
· 原因：Forge 有 impl channels is locked 机制，网络通道在 commonSetup 之后注册会失败

自检

· □ 网络包是否在构造器阶段注册？
· □ 攻击请求包处理是否刷心跳？
· □ 攻击状态包的状态机是否正确？
· □ 心跳超时机制是否生效？

7.12 防缴械机制

核心难题

玩家主动丢弃和敌人缴械最终都走同一个底层方法（背包的“设置槽位”）。必须区分。

识别点

主动丢弃一定经过特定的网络包入口，被动移除不会。

7.12.1 数据结构

锚点槽位追踪表：Map<玩家UUID, Map<槽位号, 物品快照>>

· 记录“哪个玩家的哪个槽位应该有锚点武器”
· 快照包含：物品类型 + 完整 NBT + 数量
· 注册时机：玩家每 tick 遍历背包时
· 移除时机：玩家主动丢弃后

槽位号扩展

· 主背包：0 ~ 35
· 副手：-1
· 盔甲：-2 ~ -5

允许丢弃表：Map<玩家UUID, Set<槽位号>>

· 跨 tick 记录“上一 tick 有合法的清空动作”
· 追踪检查时消费

7.12.2 主动丢弃的识别

主动丢弃入口

· ServerboundPlayerActionPacket 且 Action 是 DROP_ITEM / DROP_ALL_ITEMS
· ServerboundContainerClickPacket 且点击模式含“丢弃”
· ServerboundSetCreativeModeSlotPacket 且操作为删除
· Player.drop(ItemStack, boolean)
· ContainerMenu.quickMoveStack（快速移动，不算缴械）

在这几个入口设窗口标记（时间戳 + 允许丢弃记录）。

7.12.3 拦截点

在背包的 setItem(int slot, ItemStack stack) 入口注入：

```text
if 目标槽位在追踪表里:
    if 在丢弃窗口内:
        加入允许丢弃表
        放行
    else if 调用栈含合法入口:
        加入允许丢弃表
        放行
    else:
        cancel  // 拒绝敌人缴械
```

调用栈白名单

· handlePlayerAction
· handleContainerClick
· handleSetCreativeModeSlot
· Player.drop
· quickMoveStack
· ContainerMenu.throwClick

双重确认

· 时间窗口（快）
· 调用栈（准）
· 两个都做

7.12.4 恢复逻辑

玩家每 tick（Player.tick 尾部）执行：

```text
for (槽位, 快照) in 追踪表.get(playerId):
    当前物品 = 背包.getItem(槽位)
    
    if 当前物品不是锚点物品:
        if (playerId, 槽位) 在允许丢弃表:
            从追踪表移除该槽位
            从允许丢弃表移除
            continue  // 玩家主动丢弃
        else:
            // 被动移除 → 恢复
            背包.setItem(槽位, 快照.重建())
            打日志: "锚点武器被外部移除，已恢复"
```

快照重建

· 包含物品类型 + NBT + 数量
· 直接塞回槽位，不触发事件

7.12.5 追踪表的更新

每 tick 遍历后：

```text
for (槽位, 物品) in 背包所有槽位:
    if 物品是锚点物品:
        追踪表[槽位] = 物品快照  // 新增或更新
```

约束：不要在这里移除追踪表的槽位。移除只发生在“主动丢弃被确认”之后。

7.12.6 命令拦截

命令 是否拦截
/clear ✅
/data merge entity ✅
/item replace ✅
/give ❌
/tp ❌
/kill ❌（死亡掉落已拦）

7.12.7 特殊场景处理

场景 处理
玩家把武器放进箱子 走 handleContainerClick → 放行
玩家把武器放进潜影盒 走 quickMoveStack → 放行
玩家把武器扔进熔岩 走 handlePlayerAction → 放行
玩家用铁砧砸碎武器 走 handleContainerClick → 放行
玩家在创造模式删除 走 handleSetCreativeModeSlot → 放行
玩家登出 追踪表可持久化到 NBT，登录后恢复

7.12.8 覆盖范围

场景 覆盖
玩家死亡掉落 ✅
背包完全被清空 ✅
武器 NBT 被清 ✅
武器被火烧 ✅
武器单独从背包移除 ✅
武器被替换成其他物品 ✅
武器被 setCount(0) ✅
/clear <item> ✅
物品栏被锁定 ✅
副手武器 ✅

7.12.9 自检

· □ 追踪表覆盖了主背包 + 副手 + 盔甲槽吗？
· □ 主动丢弃的识别点有几个？都覆盖了网络包入口吗？
· □ setItem 拦截有没有调用栈检查？
· □ 允许丢弃表是跨 tick 的还是单 tick 的？
· □ 追踪表在玩家登出时清理了吗？
· □ 箱子、潜影盒、铁砧、创造模式的操作会不会被误拦？

7.13 常见现象 → 根因对照

现象 1：目标“变红抽搐”，血量满血

日志特征：

```text
路径 StorageExtract 返回 0（未清到该处，正常）
路径 MapSweep 未生效 (掩码 0x27)
路径 DirectChunkMapRemove 未生效 (掩码 0x7)
```

根因链：

· removalReason 始终是 null → isRemoved() 返回 false
· 但清集合已经成功 → 实体从 entityTickList 摘除 → 实体不再 tick
· hurtTime 不再衰减 → 客户端一直渲染红色
· 实体还在世界里 → 位置还在渲染 → “抖”

修复方向：

1. KillChain 把“设置 removalReason”提到最前
2. 清集合返回 0 是正常返回值，不抛异常，继续执行后续路径
3. entityTickList 的 SRG 名用 f_143243_

现象 2：RedefineClasses 失败: 62

日志特征：

```text
[FJ-Native] RedefineClasses 失败: 62，目标: net/minecraft/world/entity/LivingEntity
```

根因：

· SKIP_FRAMES + COMPUTE_MAXS 导致输出字节码缺 StackMapTable
· 带分支的方法验证失败

修复方向：

1. ClassReader 不 SKIP_FRAMES
2. ClassWriter 用 COMPUTE_FRAMES | COMPUTE_MAXS
3. 重写 getCommonSuperClass 用 context classloader

现象 3：Coremod patch “找不到字段”

日志特征：

```text
[某Patch]: 找不到 removalReason 字段
```

根因：

· resolveField 匹配太严格
· 或 SRG 名候选数组不全

诊断：

· 在 resolveField 里打印目标类的所有字段名 + 描述符
· 对照第三部分 SRG 名对照表
· 一次性诊断（避免刷屏）

现象 4：守卫永远不触发

根因：

· 跨 ClassLoader 通信开关被加载两份（Coremod 打包了 common 类）
· 注入的 GETSTATIC 读到另一个副本的 null

诊断：

· 用 javap 检查 Coremod jar 内容
· 确认 common 包被排除

现象 5：Agent 附加失败

日志特征：

```text
[FJ-Attach] 附加失败: java.lang.ClassNotFoundException: com.sun.tools.attach.VirtualMachine
```

根因：

· 启动器带的是裁剪过的 JRE，没有 jdk.attach 模块
· 或 Class.forName 找错 ClassLoader

修复方向：

1. 附加前先检测 VirtualMachine 是否可加载
2. 不可用时提示用户用完整 JDK 或回退 -javaagent

现象 6：Mixin 未生效

日志特征：

· 游戏启动正常，但 Mixin 注入点未生效

根因：

· mixins.json 的 package 路径与类实际包名不符
· 或 refmap 未生成
· 或 build.gradle 未配置 mixin

诊断：

· 检查 mixins.json 的 package 字段
· 检查 build.gradle 的 mixin 配置
· 检查 build/ 目录下是否生成了 refmap

现象 7：NativeGuard 把 Agent 的正常修改当成篡改

日志特征：

```text
[FJ-Native] 类保护：net/minecraft/world/entity/LivingEntity 字节不一致，已还原
[FJ-Agent] 检测到 LivingEntity 被还原，重新注入
```

根因：

· 初始化顺序错误：NativeGuard.install 在 Agent retransform 之前执行
· NativeGuard 采集的是未修改的基线
· Agent 改完后，NativeGuard 误判为外部篡改

修复方向：

· 把 NativeGuard.install 移到 Agent retransform 之后
· 详见第八部分初始化顺序

7.14 常量表

常量 值 用途
ATTACK_COOLDOWN_MS 50 攻击冷却
RANGE_RADIUS 8.0 范围攻击半径
REMOTE_DISTANCE 32.0 远程攻击距离
MELEE_REACH 6.0 近战射线距离
MAX_COS_ANGLE 0.80 视线夹角余弦阈值
POSITION_TOLERANCE 1.5 位置容差
COUNTER_ATTACK_THRESHOLD 3 反制哨兵触发次数
COUNTER_ATTACK_WINDOW_MS 5000 反制哨兵窗口
SCAN_INTERVAL_TICKS 3 服务端主动扫描间隔
ATTACK_STATE_TIMEOUT_TICKS 10 服务端攻击心跳超时
REPEAT_INTERVAL 2 客户端重复发包间隔
REPEAT_TIMEOUT 40 客户端持续按住超时
SNAPSHOT_INTERVAL_MS 1000 背包快照间隔
WARN_COOLDOWN_MS 60000 警告限流
SCAN_INTERVAL_SECONDS 5 定时 retransform 间隔
MARK_TIMEOUT_MS 30000 KillRegistry 标记超时
PRESSURE_TICKS 60 压力窗口超时
STABLE_TICKS 5 压力窗口稳定判定
TRACK_DURATION_TICKS 5 追踪攻击时长
HEALTH_DATA_ID 9 1.20.1 DATA_HEALTH_ID
REASON_KILLED 0 RemovalReason.KILLED ordinal
MAX_PROTECTED_CLASSES 64 类保护表上限
MAX_DEPTH 5 容器深度扫描上限
HEARTBEAT_INTERVAL_MS 50 心跳周期

---

7.15 我方攻击手段总表

攻击手段 实现方式 落点
AllReturn 目标实体所属模组包所有 boolean/void 方法强制返回 true/void Coremod、Native
方法体清空 第三方防御类方法体清空为 RETURN Native RedefineClasses
INVOKESPECIAL 直调父类 第三方覆写方法改成直接调父类 Native RedefineClasses
方法头插桩 对方关键方法 HEAD 处插入 return 默认值 Coremod、Agent
异常注入 对方关键方法注入 ATHROW Native RedefineClasses
常量返回替换 getHealth() 等方法体改成 return 常数 Native RedefineClasses
清空 EventBus 反射拿 listeners，直接 clear() Agent、Coremod
替换 listeners 引用 listeners 指向新的空 Map/List Agent、Coremod
摘除对方监听器 按类名/实例匹配精准移除 Agent
高优先级监听器 HIGHEST + receiveCanceled 抢先取消事件 事件层
EntityDataSweep 遍历 itemsById，float/int 改成 0 或负值 Core、KillChain
NBT 数值清洗 遍历 NBT，Health/Absorption 改成 0 Core、KillChain
反射改血 定位 DATA_HEALTH_ID，直接写 DataItem.value Core、KillChain
Unsafe 字段直写 缓存 offset，putObject/putFloat/putInt Core、KillChain
VarHandle 改字段 MethodHandles.Lookup 获取句柄 Core（备用）
修改 final 字段 Unsafe 强行写 final Core（备用）
清空实体集合 反射拿 entityTickList、ChunkMap.entityMap，直接 remove Core、KillChain
替换静态字段 改对方关键静态字段引用 Core、KillChain
深度扫容器 递归遍历对象图，移除目标引用 Core、MapSweep
加密字节码 运行时解密加载 Native、Agent
动态生成类 运行时 defineClass 生成防御类 Native、Agent
隐藏类 Lookup.defineHiddenClass 承载核心逻辑 Native
动态代理 Proxy.newProxyInstance 生成 Entity 子类 Core
ClassFileLoadHook 抢占 注册 JVMTI hook 优先返回自己的字节码 Native
Instrumentation 反复覆盖 定时 retransform，覆盖对方注入 Agent
Mixin 后撤销 检测到 Mixin 后 retransform 还原 Agent ForcedClassRewrite
JVMTI 能力竞争 申请 can_redefine_classes，让对方不可用 Native
NativeRedefineAll JVMTI 改第三方 boss 类方法体 Native
NativeKill JNI 直写 removalReason Native
UnsafeWrite Unsafe.putObject 写 removalReason Core
StorageExtract 6 处集合摘除 Core
MapSweep 递归扫容器移除目标 Core
ZeroHealthDie 血量归零 + die Core、KillChain

7.16 我方防御手段总表

防御手段 实现方式 落点
六层并存 事件 + Mixin + Coremod + Agent + Native + Bootstrap 全程
失败隔离 每条路径独立 try-catch 全程
KillValidator 字段级 不依赖 isRemoved()，直接读 removalReason 9b
6 维位掩码 IN_ISREMOVED / IN_REMOVALFIELD / IN_LEVEL / IN_SECTION / IN_TICKLIST / IN_CHUNKMAP 9b
多来源交叉验证 level.getEntity + entityTickList + ChunkMap.entityMap + section.storage 9b
EventBusGuard 保护 6 类 DefenseHandler / LoopHijacker / EntityListGuard / AttackEventHandler / ServerAutoAttack / IsolationManager 10
EventBusGuard 自保护 自己加入保护列表 10
Agent 定时反射重注册 每 5 秒通过反射调用重注册 3、7c
EventBusAnchor 双检查 检查引用替换 + 内容清空 10
非事件总线自愈 至少一条路径不经过 EventBus 也能重注册 3、7c
NativeGuard 类保护 MAX_PROTECTED_CLASSES = 64 8
双基线校验 jar 原始字节 + 首次运行时捕获字节 8
方法级指纹 ASM 提取方法体 SHA-256 8
NativeGuard 时序 在 Agent retransform 和 ForcedClassRewrite 之后 6、8
ForcedClassRewrite Mixin 之后用 Instrumentation.retransformClasses 3、7b
Native RedefineClasses JVMTI 改第三方类，禁止 vanilla 2、8
运行时字节码捕获 RetransformClasses 触发 hook 2
多时机捕获 不同时间点多次 retransform 2、8
动态生成类检测 ClassFileLoadHook 记录 2、5.4
隐藏类检测 JVMTI 能力枚举 + ClassFileLoadHook 2、6.5
行为分析 观察哪些类覆写了 hurt/setHealth/isRemoved 8
可疑度评分 方法名、指令数、覆写特征，>= 4 判可疑 8
血量 Native 优先 值存 C 层，Java 堆扫不到 2、7a
影子血量三基线 工作值 + 上限值 + 永久基线 7a
Mixin accessor 优先 itemsById、DataItem.value 走 refmap 7a
EntityData 直写 绕过 set() 直接写 DataItem.value 7a
Unsafe 直写 缓存 offset，putObject/putFloat 7a
禁止 setAccessible 改用 Unsafe 直写 7a
字段名集中管理 双候选只在映射表出现 1
描述符四级回退 名字+描述符 → 只名字 → 只描述符 → 关键字模糊 1
FieldIsolation 加 instanceof Player 避免非玩家实体误隔离 7a
能力位掩码查询 启动时查 can_redefine_classes 2
JVMTI 竞争降级 对方占用时切换非 JVMTI 路径 2
检测 jdk.attach 附加前先 Class.forName 3、6
回退 -javaagent jlink 裁剪 JRE 时提示 3、6
子 JVM 附加 绕过 JDK 9+ 自附加禁令 3、6
轮询等待 每 100ms 检查，最多 5 秒 3、6
我方标记计数器 g_self_retransform_count 原子加/减 2、3
注册后复查 + 黑名单 避免反复注册 10
警告限流 60 秒 避免每 tick 刷屏 10
防缴械允许丢弃表 区分主动丢弃和被动移除 10
锚点槽位追踪表 主背包 0–35、副手 -1、盔甲 -2 至 -5 10
setItem 拦截双重确认 时间窗口 + 调用栈白名单 10
命令拦截 /clear、/data merge entity、/item replace 10
Native 存储关键值 血量、开关、标记存 C 层 2、7a
Bootstrap 共享常量 锚点 key/value 注入 Bootstrap CL 4
KillFlag 唯一加载 Coremod 不打包 common 类 1、5
日志前缀规范 便于过滤和调试 全程
反射审计开关 只在 -Dfj.reflection.audit=true 时运行 7c

7.17 隐藏关键字段（Deception 层）

核心思路：真实值存 Native，Java 层只放诱饵，按调用者区分返回。

隐藏字段优先级：

1. 血量（DATA_HEALTH_ID）
2. removalReason
3. isRemoved
4. NBT Health / Absorption
5. UUID / EntityID 映射

落点：

· Mixin：SynchedEntityData、Entity.isRemoved、Entity.removalReason、LivingEntity.getHealth
· Coremod：纯字节码守卫，按调用者返回诱饵
· Native：HealthEntry 扩展 realRemoval / realIsRemoved
· NBT：CompoundTag 视图污染

调用者区分：

· ClassLoader 判断 + ThreadLocal 标记
· 自己代码调用前设 DeceptionContext.enter()
· Mixin 注入点检查 DeceptionContext.isSelf()
· 是 → 返回真值；否 → 返回诱饵

KillValidator 适配：

· 从 Native 读真值，不依赖 Java 层方法返回值

7.18 反传送机制

目标：对方瞬移时仍能命中，持有者被传送/移除时能恢复。

实现：

· 位置校验：deltaMovement、chunkPosition、feetBlockState
· 传送后恢复：setPosRaw、setBoundingBox、makeBoundingBox
· 传送后重新瞄准：calculateViewVector
· 传送后重新追踪：ChunkMap.updatePlayerStatus、TrackedEntity.serverEntity、seenBy、ServerEntity.entity
· 传送后查找：EntityLookup.byUuid、byId
· 追踪攻击连续 5 tick 重定位

落点：

· TrackingAttack
· EntityListGuard
· Deception 层
· Native 集合操作

7.19 动态生成类与隐藏类对抗

目标：识别、捕获、分析运行时动态生成的类。

实现：

· ClassFileLoadHook 记录所有类加载事件
· 定期 GetLoadedClasses 枚举
· 行为分析替代静态分析
· 对隐藏类尝试 JVMTI 能力枚举
· 动态类注入由 Agent 定时 retransform 扫描

落点：

· Native 层：ClassFileLoadHook、GetLoadedClasses、行为分析
· Agent 层：定时 retransform 扫描
· Coremod 层：通配符 targets

7.20 ECA 数据流改血反制

目标：反制三阶段递进改血。

实现：

· KillValidator 不依赖 getHealth() 返回值，直接读 Native 真实值或 removalReason
· 真实血量完全不在 Java 堆，Java 层只放诱饵
· NativeGuard 检测 getHealth() 被改并还原
· Coremod 守卫纯字节码比较，不调用 Java 方法
· 影子血量三基线 + 每 tick 校验

落点：

· Deception 层
· Native 层
· Coremod 守卫
· KillValidator

7.21 分层降级与回退路径

核心原则：Native 是首选，不是唯一。

分层降级总表：

能力 首选 回退 1 回退 2 回退 3
直杀实体 NativeKill UnsafeWrite MixinAccessorWrite VanillaSetRemoved
改方法体 NativeRedefineAll Agent ASM Coremod 守卫 Mixin 注入
集合摘除 Native 集合操作 Unsafe 读字段 + remove 反射读字段 + remove MapSweep
血量存储 Native HealthEntry Java 加密 Map 内存混淆 Map 原版 DATA_HEALTH_ID
类保护 NativeGuard Agent SelfClassProtector Coremod 守卫 方法级指纹
我方标记 Native 计数器 Bootstrap AtomicInteger 系统属性 ThreadLocal
日志桥接 Native JNI 回调 Bootstrap 共享日志类 系统属性 直接 Java 日志
Deception 真值 Native HealthEntry Java 加密 Map 内存混淆 不做 Deception
动态类检测 Native ClassFileLoadHook Agent ClassFileTransformer Coremod 通配符 行为分析
类枚举 Native GetLoadedClasses Instrumentation.getAllLoadedClasses ClassLoader 递归 不做枚举
字节码捕获 Native RetransformClasses Instrumentation.retransformClasses Agent ClassFileTransformer 从 jar 读原始字节

关键路径降级链：

直杀实体：
NativeKill → UnsafeWrite → MixinAccessorWrite → VanillaSetRemoved → StorageExtract → MapSweep → 压力窗口

改方法体：
NativeRedefineAll → Agent ASM → Coremod 守卫 → Mixin 注入 → ForcedClassRewrite

血量存储：
Native HealthEntry → Java 加密 Map → 内存混淆 → 原版 DATA_HEALTH_ID

类保护：
NativeGuard → Agent SelfClassProtector → Coremod 守卫 → 方法级指纹

我方 retransform 标记：
Native 计数器 → Bootstrap AtomicInteger → 系统属性 → ThreadLocal

Deception 真值存储：
Native HealthEntry → Java 加密 Map → 内存混淆 → 不做 Deception

每层独立启动检查：

· NativeBridge.load()：平台检测 → 提取 DLL → System.load → nativePing，任一步失败 nativeReady = false，打 WARN，不抛异常
· AgentBridge.getInstrumentation()：反射读 InstHolder，失败 agentReady = false，debug 日志
· Coremod 检测：检查系统属性 fj.coremod.loaded，失败 coremodReady = false
· Bootstrap 注入：Native 不可用则跳过，bootstrapReady = false

状态可视化：
武器 tooltip 显示四层状态，Native 不可用时显示 §c✗

降级路径验证：

1. 关掉 Native：Agent + Coremod + Mixin 仍能完成秒杀
2. 关掉 Agent：Native + Coremod + Mixin 仍能完成秒杀
3. 关掉 Coremod：Native + Agent + Mixin 仍能完成秒杀
4. 关掉 Mixin：Native + Agent + Coremod 仍能完成秒杀
5. 全部关掉：至少事件层 + Unsafe 能完成部分功能

---

第八部分：初始化顺序

8.1 构造器阶段

1. 注册物品
2. 注册网络包
3. 注册 commonSetup 监听器

为什么网络包在构造器阶段：Forge 有“impl channels is locked”机制，网络通道在 commonSetup 之后注册会失败。

8.2 commonSetup 阶段（完整 25 步）

1. NativeBridge.load() + 自检
2. Native 能力查询 + 日志
3. Native retransform 测试（仅开发环境）
4. 注册 Native 日志桥接（nativeInitLogger）
5. 注册 Native 类保护回调（nativeInitGuard）
6. 反射审计（可选，-Dfj.reflection.audit=true）
7. Agent 探测（AgentBridge.getInstrumentation）
8. Agent 附加（AgentAttacher.attachFromResource）
9. 注册 MainClassTransformer
10. retransform LivingEntity（Agent 层）
11. 启动 PeriodicRetransformer
12. ForcedClassRewrite.init
13. 类保护安装（NativeGuard.install）—— 必须在 Agent retransform 和 ForcedClassRewrite 之后，否则会把 Agent 的正常修改当成篡改
14. T0Anchor.init
15. AttackEventHandler.init
16. DefenseHandler.init
17. EventBusAnchor.init
18. EventBusGuard.init
19. IsolationManager.init
20. EntityListGuard.init
21. LoopHijacker.init
22. HeartbeatThread.start
23. ServerAutoAttack.init
24. 三条 Native 路线（Bootstrap → Analysis → Redefine）
25. Client 模块（仅客户端环境）

顺序原因：

· Native 先：其他层可能依赖 JVMTI 能力
· Agent 次：注册 transformer 后才能 retransform
· ForcedClassRewrite 再次：抢在 Mixin 之后、NativeGuard 之前
· NativeGuard 在 Agent 和 ForcedClassRewrite 之后：基线必须在所有变换完成后采集，否则会把 Agent 的正常修改当成外部篡改
· LoopHijacker 最后：它调用其他模块

8.3 每个 init 的独立 try-catch

· 任一模块初始化失败不影响后续
· 失败时打 WARN 级日志
· 四层状态 tooltip 反映实际就绪状态

---

第九部分：开发规范（必须遵守）

# 规范 违反后果
1 SRG 名集中管理 生产环境静默失效
2 Agent 层 ASM：若用 COMPUTE_FRAMES 必须重写 getCommonSuperClass（context classloader）；插入分支用 COMPUTE_FRAMES \| COMPUTE_MAXS；COMPUTE_MAXS 不生成栈帧 VerifyError，类加载失败
3 Native 层 RedefineClasses 用 COMPUTE_FRAMES \| COMPUTE_MAXS，ClassReader 不 SKIP_FRAMES，过滤 vanilla JVMTI_ERROR_FAILS_VERIFICATION (62)
4 武器 use() 返回 pass() 客户端不发包，右键失效
5 Coremod 独立 jar + -transformer 后缀；MANIFEST.MF 用 FMLModType；mods.toml 无 modType Coremod 完全不加载
6 RedefineClasses 用 jvmtiClassDefinition 参数类型错，编译失败
7 血量 Native 优先 反编译工具能扫到值
8 KillValidator 综合判断，不只看 removalReason 反射失败时误判
9 KillChain 每条路径独立 try-catch 一条失败全链中断
10 同一方法只由一层负责 多层改同一方法互相破坏
11 Coremod 字段名用描述符解析，不只靠双候选 生产环境静默失效
12 nativeSetSelfRetransform 包裹我方 retransform，用计数器而非布尔 NativeGuard 自伤 或 多线程标记丢失
13 反射字段名用集中管理表 硬编码漏改
14 事件处理器加 HIGHEST + receiveCanceled 被其他 mod 取消
15 LoopHijacker 用 LOWEST + END 抢不到最后执行时机
16 心跳线程只做原子写 跨线程访问 MC 对象崩溃
17 Native 重定义跳过 net.minecraft.* 影响所有实体，风险大
18 FieldIsolation.isIsolated 加 instanceof Player 判断 非玩家实体被误隔离
19 ReflectionAudit 只在 -Dfj.reflection.audit=true 时调用 性能浪费
20 Coremod 与主模块通信必须走 KillRegistry 反射，确保唯一加载，Coremod 不打包 common 类 ClassLoader 隔离导致直接引用失败
21 跨 ClassLoader 静态字段不共享，KillFlag 必须唯一加载且位于 game 层（coremod） 检查永远 false 或 NoClassDefFoundError（被 try-catch 吞掉 → 静默失行为）
22 Agent 动态附加依赖 jdk.attach；标准 JDK 17 默认包含，不需要 --add-modules；jlink 裁剪过的 JRE 才需回退 附加失败
23 Bootstrap 类不能引用 MC / mod 类；能引用任意 JDK 类 NoClassDefFoundError
24 守卫注入按返回类型生成 return VerifyError 或逻辑错误
25 Minecraft 类仅客户端，Coremod 分侧 服务端崩溃
26 防缴械用“允许丢弃表”区分主动和被动 玩家丢不掉武器 或 敌人能缴械
27 锚点槽位追踪扩展副手 + 盔甲槽 副手武器被缴械无防护
28 coremod / agent / common 用 compileOnly ASM 时，只使用 ASM 9.5 已存在的 API 生产环境 NoSuchMethodError
29 NativeGuard.install 必须在 Agent retransform 和 ForcedClassRewrite 之后 把 Agent 的正常修改当成篡改
30 禁止使用 Field.setAccessible(true) 访问 java.base 私有成员；改用 Unsafe 直写 引入 --add-opens 依赖，生产环境启动失败
31 ForcedClassRewrite 用 Instrumentation.retransformClasses（允许 vanilla）；Native Redefine 用 JVMTI（禁止 vanilla） 两种重定义混淆导致行为不一致
32 日志前缀统一：主 mod [FJ]，Native [FJ-Native]，Coremod [FJ-Coremod]，Agent [FJ-Agent]，Bootstrap [FJ-Bootstrap] 日志难以过滤
33 Deception 层真实值只存 Native，Java 层只放诱饵；按调用者区分返回 对方读到诱饵，自己人读真值
34 KillValidator 从 Native 读真值，不依赖 getHealth / isRemoved 方法返回值 反制“所有方法返回 TRUE”与 ECA 字节码逆向
35 隐藏字段优先级：血量 > removalReason > isRemoved > NBT > UUID 映射 逐步覆盖对方判死逻辑
36 调用者区分用 ClassLoader + ThreadLocal，自己代码调用前设标记 误判导致自己人拿到诱饵
37 反传送：追踪攻击连续 5 tick 重定位 + 位置校验 deltaMovement / chunkPosition 对方瞬移后仍能命中
38 动态生成类与隐藏类：ClassFileLoadHook 记录 + 行为分析替代静态分析 静态分析失效时仍能识别
39 每层必须有独立降级路径，Native 不是唯一依赖 单层失败全盘瘫痪
40 Native 加载失败不抛异常，打 WARN 日志，nativeReady = false 阻塞其他层初始化
41 关键路径至少三条独立实现：Native / Unsafe / Mixin 一条失败全链中断
42 血量存储降级：Native → Java 加密 Map → 内存混淆 → 原版 反编译工具能扫到明文
43 类保护降级：NativeGuard → Agent SelfClassProtector → Coremod 守卫 → 方法级指纹 类被篡改无法检测
44 我方 retransform 标记降级：Native 计数器 → Bootstrap AtomicInteger → 系统属性 → ThreadLocal 跨 CL 标记丢失
45 Deception 真值存储降级：Native → Java 加密 Map → 内存混淆 → 不做 Deception Native 不可用时真值暴露
46 每层独立启动检查，任一层失败不影响其他层 初始化顺序错误导致连锁失败
47 武器 tooltip 显示四层状态，玩家能看出哪层降级 降级不可见
48 攻击手段必须从我方视角实现，不依赖对方是否存在 只能防守不能反击
49 AllReturn 只对目标实体所属模组包生效，不波及 MC/Forge 避免影响原版逻辑
50 方法体清空/INVOKESPECIAL 改写只对第三方防御类生效，跳过 net.minecraft.* 误伤原版实体
51 EntityDataSweep / NBT 清洗只在 KillChain 触发时执行，不每 tick 扫 性能开销
52 清空 EventBus / 替换 listeners 引用只在自愈路径中使用，不主动破坏对方 误伤自己监听器
53 我方攻击手段同样需要 try-catch + KillValidator 校验 攻击失败不阻塞后续路径

---

第十部分：任务清单（修复版）

本部分为 AI 下派任务的唯一入口。
每个 Unit 必须按模板执行：必读原文 → 适用规范 → 设计 → 实现 → 自检 → 证据 → 人类验收 → 完成定义。
未通过当前 Unit 验证，不得进入下一 Unit。

---

10.0 前置准备（开工前必做，不算 Unit）

在开始任何 Unit 之前，先完成：

· 建多模块工程骨架：settings.gradle / 根 build.gradle / 6 个子模块目录（common、native、agent、bootstrap、coremod、main）。
· 根 build.gradle 配置全局编码 + ForgeGradle + Mixin 插件声明。
· gradle.properties 写入 gcc.path=C:/mingw64/bin/gcc.exe。
· 确认 gradlew build 通过（若无法完成，可要求用户提供结果）。

参考：ENV_SPEC.md 第三、六、八节。

---

10.0.1 跨模块约束总表（每个 Unit 开始前必读）

约束 涉及 Unit 说明
KillFlag 必须唯一加载 1、5 Coremod 不能打包 common 类，否则静态字段不共享，注入的 GETSTATIC 读到另一个副本；**且 KillFlag 必须位于 game 层（coremod jar）**，放 common 会导致 MC 类解析不到 → NoClassDefFoundError 被守卫 try-catch 吞掉（实测：生物死亡不消失、持续掉战利品）
NativeGuard.install 时序 6、8 必须在 Agent retransform 和 ForcedClassRewrite 之后，否则把 Agent 正常修改当成篡改
网络包必须在构造器阶段注册 6、11a 否则 Forge 报 impl channels is locked
Minecraft 类仅客户端 5、11b Coremod 必须分侧，服务端不能无条件注入 Minecraft.runTick
ForcedClassRewrite 处理 vanilla，Native Redefine 禁止 vanilla 3、2、8 两者目标类不重叠，混淆会导致行为不一致
禁止 setAccessible 访问 java.base 6、7a、7b、8 改用 Unsafe 直写，避免引入 --add-opens 依赖
Agent ASM 必须用 context classloader 3、7b、8 getCommonSuperClass 不能抛异常，失败返回 java/lang/Object
同一方法只由一层负责 全程 多层改同一方法会互相破坏
SRG 名集中管理 1、5、7、8 唯一写 SRG 名的地方在 common 映射表
common 用 compileOnly ASM 1、5、8 运行期不打包 ASM，由调用方提供
守卫按返回类型生成 return 5 void→RETURN，boolean→ICONST_0/1 + IRETURN，其他类型按需

---

10.1 执行原则

· 一次一个 Unit，未通过验证不得进入下一 Unit。
· 每个 Unit 先设计，等人类确认后再写码。
· 确认后 AI 自己写码、编译、读日志、修错，直到验证通过。
· 禁止读取 legacy/、old/。
· 禁止修改 IMPLEMENTATION_SPEC.md、ENV_SPEC.md。
· 禁止一次做多个 Unit。
· 禁止跳过自检清单和证据要求。

10.2 每个 Unit 的流程

阶段 A：设计

· 读对应章节全文。
· 输出：
  · 本 Unit 对应原文小节
  · 适用规范编号
  · 类列表 + 字段/方法签名 + 依赖关系
  · 自检清单预览
  · 验证命令
  · 证据要求
· 停下等人类确认。

阶段 B：实现

· 严格按设计写码，不新增设计外的类。
· 对照第九部分自检。
· 跑验证命令。
· 失败读 build/reports 和 run/logs/latest.log 修，直到通过。
· 连续失败 5 次无进展，停下报告。

阶段 C：报告

· 创建的文件清单。
· 验证命令 + 结果。
· 自检清单逐条打勾结果。
· 证据（日志片段、javap 结果、截图等）。
· 需要人类验收的项。
· 已知问题或降级。
· 本 Unit 对应原文小节和规范编号。

---

10.3 工作单元（修复版）

大 Unit 已拆分：7a/7b/7c、9a/9b/9c、11a/11b。
每个 Unit 必须按统一模板执行。

执行顺序：

```
Unit 0b → Unit 1 → Unit 2 → Unit 3 → Unit 4 → Unit 5 → Unit 6
→ Unit 7a → Unit 7b → Unit 7c → Unit 7d → Unit 8
→ Unit 9a → Unit 9b → Unit 9c → Unit 9d
→ Unit 10 → Unit 10b
→ Unit 11a → Unit 11b
→ Unit 12
```

---

Unit 0b：降级路径验证

必读原文：7.21、7.9 扩展 15–16、第九部分规范 39–47
适用规范编号：39、40、41、42、43、44、45、46、47

目标
验证每层独立降级，确保 Native 不可用时其他层仍能工作。

产出物

· 四层状态检查：nativeReady / agentReady / coremodReady / bootstrapReady
· 武器 tooltip 四层状态显示
· 降级路径日志：[FJ] Native 不可用，回退到 UnsafeWrite
· 关掉 Native 的测试脚本

关键约束

· 每层独立启动检查，任一层失败不阻塞其他层
· 关键路径至少三条独立实现
· 武器 tooltip 显示四层状态

自检清单（逐条打勾）

· □ 关掉 Native，Agent + Coremod + Mixin 是否仍能完成秒杀？
· □ 关掉 Agent，Native + Coremod + Mixin 是否仍能完成秒杀？
· □ 关掉 Coremod，Native + Agent + Mixin 是否仍能完成秒杀？
· □ 关掉 Mixin，Native + Agent + Coremod 是否仍能完成秒杀？
· □ 武器 tooltip 是否显示四层状态？
· □ Native 加载失败是否打 WARN 日志而不抛异常？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志；自检清单逐条打勾。

人类验收

· 关掉任一层，其他层仍工作。

完成定义

· 验证命令通过；自检清单全部打勾；人类验收通过。

---

Unit 1：common 模块

必读原文

· 第三部分全部（3.1–3.4）
· 7.14 常量表全部
· 第九部分规范 1、7、11、13、20、21、24、30、32

适用规范编号
1、7、11、13、20、21、24、28、30、32

目标
提供所有模块共用的契约：SRG 名集中管理、全局常量、状态接口、KillFlag。

产出物

· SRGNames 类（或同等）：双候选数组 + match 工具方法 + resolveField(ClassNode, String[], String) 四级回退（需要 compileOnly ASM，运行期由调用方提供）
· Constants 类：MOD_ID、锚点 key、系统属性名、资源路径
· FJState 接口：isReady() + getName()
· KillFlag 类：public static volatile Object targetEntity / protectedPlayer
· 锚点 key/value 常量副本（与 Bootstrap 值一致）

关键约束

· SRG 名是唯一写 f_ / m_ 字符串的地方。
· resolveField 必须四级回退：名字+描述符 → 只名字 → 只描述符 → 关键字模糊匹配。
· 首次失败必须打印目标类所有字段名+描述符+期望候选+一次性 boolean。
· Coremod 构建必须排除主模块 common 包。
· KillFlag 必须保证唯一加载。
· common 用 compileOnly ASM，运行期不打包 ASM，由调用方提供。

自检清单（逐条打勾）

· □ 全项目搜 "f_" 开头的字符串，是否只在映射表里出现？
· □ resolveField 有没有四级回退？
· □ 失败时打印的是“我找不到字段”还是“目标类有哪些字段”？
· □ Coremod 构建产物里有没有 common 包？
· □ 用 javap 检查 Coremod jar 内容，确认没有重复类。
· □ 描述一下“如果 KillFlag 被加载两份，会看到什么现象？”

验证命令

```bash
gradlew.bat :common:build
```

证据要求

· 命令输出。
· javap 检查结果。
· 自检清单逐条打勾结果。

人类验收

· 看类结构，SRG 名集中在一处。
· Coremod 产物里无 common 包。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。
· 无未说明的降级。

禁止

· 不得新增设计外的类。
· 不得跳过自检。
· 不得修改 IMPLEMENTATION_SPEC.md / ENV_SPEC.md。

---

Unit 2：native 模块

必读原文

· 第六部分全部（6.1–6.5）
· 第九部分规范 3、12、23、24、30、31

适用规范编号
3、12、23、24、30、31

目标
提供 JVMTI 能力，实现 Native 层兜底。

产出物

· finaljudgment_native.dll（Windows x86_64）
· JNI 生命周期：JNI_OnLoad / JNI_OnUnload
· 全局变量：g_jvm、g_jvmti、日志桥接、守卫回调、类保护表、捕获现场、替换现场、我方标记计数器、血量存储链表
· 功能 0–13 全部实现

关键约束

· MAX_PROTECTED_CLASSES = 64。
· RedefineClasses 必须用 jvmtiClassDefinition。
· 提交字节码必须包含完整 StackMapTable，Java 侧用 COMPUTE_FRAMES | COMPUTE_MAXS，ClassReader 不 SKIP_FRAMES。
· 不能对 net.minecraft.* 的 vanilla 类调用 RedefineClasses。
· 我方 retransform 标记用计数器，原子加/减。
· 日志桥接优先 JNI 回调，失败回退 fprintf(stderr)。
· Bootstrap 注入用 AddToBootstrapClassLoaderSearch，一旦注入不可撤回。

自检清单（逐条打勾）

· □ JNI_OnLoad / JNI_OnUnload 是否完整？
· □ 4 段 hook 逻辑（捕获/保护/替换/放行）是否按优先级判断？
· □ RedefineClasses 是否用 jvmtiClassDefinition？
· □ 是否用 COMPUTE_FRAMES | COMPUTE_MAXS？
· □ ClassReader 是否不 SKIP_FRAMES？
· □ 是否过滤了 net.minecraft.*？
· □ 我方标记是否用计数器？
· □ 日志桥接是否先 AttachCurrentThread？
· □ Bootstrap 类是否不引用 MC/mod 类？
· □ NativeGuard.verifyAndRepair 签名是否为 (Ljava/lang/String;[B)[B？

验证命令

```bash
gradlew.bat :native:compileNative
```

证据要求

· 确认 native/build/native/windows-x86_64/finaljudgment_native.dll 存在。
· 自检清单逐条打勾。

人类验收

· 确认 dll 路径正确。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

上下文不足时自行拆分

· 2a：功能 0–3 + 9 + 12
· 2b：功能 4–8 + 10 + 11 + 13

---

Unit 3：agent 模块

必读原文

· 第四部分全部（4.1–4.4）
· 第九部分规范 2、12、22、32

适用规范编号
2、12、22、32

目标
在 JVM 启动早期注册 ClassFileTransformer，允许主 mod 动态改类。

产出物

· finaljudgment-agent.jar
· InstHolder：持有 Instrumentation
· AgentAttacher：动态附加
· PeriodicRetransformer：每 5 秒 retransform
· ForcedClassRewrite：Mixin 之后最后一道改写
· MainClassTransformer
· MANIFEST.MF 三件套 + Can-Redefine-Classes: true / Can-Retransform-Classes: true

关键约束

· 不能用 VirtualMachine.attach(自身 PID)。
· 先检测 jdk.attach 是否可用，标准 JDK 17 默认包含。
· 附加超时 10 秒，轮询等待 Instrumentation 最多 5 秒。
· 定时 retransform 前后调 nativeSetSelfRetransform(true/false)，跨 ClassLoader 必须反射或走共享层。
· ASM getCommonSuperClass 必须用 Thread.currentThread().getContextClassLoader()。
· COMPUTE_MAXS 只算 maxStack/maxLocals，插入分支必须用 COMPUTE_FRAMES | COMPUTE_MAXS。
· ForcedClassRewrite 用 Instrumentation.retransformClasses，允许 vanilla。

自检清单（逐条打勾）

· □ 附加前有没有检测 jdk.attach？
· □ 附加失败时，日志能看出是哪一步失败吗？
· □ 定时 retransform 前后，有没有调“我方标记”？
· □ 我方标记是通过什么方式跨 ClassLoader 传递的？
· □ ASM 的 getCommonSuperClass 用的是哪个 ClassLoader？
· □ ForcedClassRewrite 用的是 Instrumentation 还是 JVMTI？

验证命令

```bash
gradlew.bat :agent:jar
```

证据要求

· 解压看 MANIFEST 三件套 + 两个 Can-*。
· 自检清单逐条打勾。

人类验收

· 解压看 MANIFEST 三件套 + 两个 Can-*。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 4：bootstrap 模块

必读原文

· 2.3 节 Bootstrap 部分
· 第六部分功能 10
· 第九部分规范 23

适用规范编号
23

目标
独立 submodule，独立打成 jar，只放常量和基础类型，供 Bootstrap ClassLoader 加载。

产出物

· finaljudgment-bootstrap.jar
· 锚点 key / value / 系统属性名 / 跨 ClassLoader 开关

关键约束

· 不 import 任何 net.minecraft.* / net.minecraftforge.* / com.maha_fish.finaljudgment.*。
· 可引用任意 JDK 类（java.* / javax.* / jdk.*）。
· 与 common 的对应常量值一致。
· 一旦注入不可撤回。

自检清单（逐条打勾）

· □ Bootstrap jar 里有几个类？是否全部只有常量？
· □ 用 javap 检查，是否有任何 MC / Forge 类型的引用？
· □ 主模块能通过 ClassLoader.getSystemClassLoader().getParent() 读到这些常量吗？

验证命令

```bash
gradlew.bat :bootstrap:jar
```

证据要求

· javap 检查结果。
· 自检清单逐条打勾。

人类验收

· javap 检查无 MC / Forge 引用。
· 确认能通过 ClassLoader.getSystemClassLoader().getParent() 读到常量。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 5：coremod 模块

必读原文

· 第五部分全部（5.1–5.5）
· 第九部分规范 5、11、20、24、25、32

适用规范编号
5、11、20、24、25、32

目标
在 ModLauncher 加载阶段介入字节码，优先于其他 mod。

产出物

· finaljudgment-coremod-transformer.jar
· ITransformationService 实现
· META-INF/services/cpw.mods.modlauncher.api.ITransformationService
· MANIFEST.MF 声明 FMLModType: GAMELIBRARY
· mods.toml（不含 modType）
· 目标类字节码注入框架
· 守卫注入（按返回类型生成 return）
· CoremodGuard、KillRegistry、CoremodKillTicker

关键约束

· jar 名必须以 -transformer 结尾。
· 不能用 jarJar 打包。
· onLoad 设系统属性 fj.coremod.loaded=true。
· 字段解析必须从 ClassNode 解析实际名字，四级回退。
· 守卫纯字节码比较，不调用任何 Java 方法。
· ServerLevel.removeEntityComplete 找不到时打 INFO 跳过。
· Minecraft.runTick 仅客户端。
· Coremod 不能打包 common 类，与主模块通信走 KillRegistry 反射。

自检清单（逐条打勾）

· □ 所有守卫都按返回类型生成了正确 return 吗？
· □ 有没有不小心调用 Java 方法？（搜 INVOKE 指令）
· □ 字段解析失败时，打印了目标类的所有字段吗？
· □ Minecraft.runTick 有没有分侧？
· □ 独立守卫类有没有 import 主模块的类？
· □ 它由哪个 ClassLoader 加载？
· □ ServerLevel.removeEntityComplete 找不到时是否安全跳过？

验证命令

```bash
gradlew.bat :coremod:jar
```

证据要求

· 解压检查 5 项：-transformer / FMLModType / services / mods.toml / 无 modType。
· 自检清单逐条打勾。

人类验收

· 解压检查 5 项结构。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 6：主 mod 骨架

必读原文

· 第七部分 7.1 + 7.2 + 7.3 功能 1、2
· 第八部分全部
· 第九部分规范 22、29、32

适用规范编号
22、29、32

目标
Forge 能加载主 mod，按 8.2 的 25 步顺序初始化各层。

产出物

· @Mod 主入口
· 初始化调度：严格按 8.2 的 25 步
· 状态查询：NativeBridge.isLoaded / AgentBridge.isReady / 系统属性 fj.coremod.loaded / Bootstrap 常量可读性
· 日志前缀规范
· AgentBridge 反射读 Instrumentation
· AgentAttacher 动态附加
· PeriodicRetransformer
· NativeBridge JNI 声明与加载
· nativeLog 日志接收
· 构造器阶段：物品 / 网络包 / commonSetup 监听器
· 每个 init() 独立 try-catch + WARN 级日志

关键约束

· 网络包必须在构造器阶段注册。
· NativeGuard.install 必须在 Agent retransform 和 ForcedClassRewrite 之后。
· NativeBridge.load() 平台检测：os.name + os.arch，本项目仅 Windows x86_64。
· 提取 native 到临时文件，deleteOnExit，System.load。
· 调 nativeInitLogger 注册日志桥接，调 nativePing 探针。

自检清单（逐条打勾）

· □ 网络包是否在构造器阶段注册？
· □ 初始化顺序是否严格按 8.2 的 25 步？
· □ 每个 init() 是否独立 try-catch？
· □ 日志是否有 [FJ] / [FJ-Agent] / [FJ-Native] 前缀？
· □ NativeGuard.install 是否在 Agent retransform 和 ForcedClassRewrite 之后？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志片段。
· 自检清单逐条打勾。

人类验收

· 日志有 [FJ] / [FJ-Agent] / [FJ-Native] 前缀。
· 无三大 Error。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 7a：Core 层（内存工具 + 状态管理）

必读原文

· 第七部分 7.4.1–7.4.2 全部
· 第九部分规范 7、18、30

适用规范编号
7、18、30

目标
实现 Unsafe 访问、字段直写、血量字段读写、SynchedEntityData 内部直写、影子血量、武器锚点。

产出物

· UnsafeAccess：主路径 MethodHandles.privateLookupIn(sun.misc.Unsafe).findStaticGetter（零 JVM 参数、不 setAccessible）；**不设 IMPL_LOOKUP 备用**（读它必须 setAccessible，与规范 30 冲突）
· FieldWriter：缓存 offset + putObject，不走 setAccessible
· HealthField：定位 DATA_HEALTH_ID，Mixin accessor 优先，兜底反射
· SynchedEntityDataWriter：itemsById + DataItem.value
· ShadowHealth：Native 优先 + Java 兜底 + 三基线 + 每 tick 校验 + instanceof Player
· T0Anchor：NBT 标记 + hasAnchorInInventory + 两条重写路径

关键约束

· 禁止 setAccessible 访问 java.base，一律走 Unsafe 直写。
· FieldIsolation.isIsolated 必须加 instanceof Player 判断。
· 血量三基线：工作值 + 上限值 + 永久基线。
· 校验与修复流程每 tick 执行。

自检清单（逐条打勾）

· □ 是否使用 setAccessible 访问 java.base？
· □ FieldIsolation.isIsolated 是否加了 instanceof Player？
· □ 影子血量是否有三基线？
· □ 每 tick 是否校验影子完整性？
· □ 武器锚点是否只重写已有锚点，避免误加？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志无 VerifyError。
· 自检清单逐条打勾。

人类验收

· Unsafe 直写生效。
· 影子血量可读写。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 7b：Core 层（类处理 + 实体管理）

必读原文

· 第七部分 7.4.3–7.4.4 全部
· 第九部分规范 2、3、10、29

适用规范编号
2、3、10、29

目标
实现从 jar 读原始字节码、ASM 方法体改写、主 Transformer、自身类保护、6 处集合摘除、深度扫容器、实体重注入。

产出物

· JarBytecodeReader：四级回退 + 空数组缓存 + union 协议
· MethodRewriter：COMPUTE_FRAMES | COMPUTE_MAXS + context classloader + 幂等
· MainTransformer：只处理 LivingEntity
· SelfClassProtector：前缀匹配 + 跳过合成类 + 基线按类来源
· ForcedClassRewrite
· StorageExtract：6 处集合摘除
· MapSweep：限深 5 层 + IdentityHashMap + 快照遍历
· EntityReinjector：entityManager.addEntity + Object 参数

关键约束

· 6 处位置必须全摘：section.storage / visibleEntityStorage / knownUuid / entityTickList.active / entityTickList.passive / ChunkMap.entityMap。
· 字段名走描述符解析，不只靠双候选名。
· 返回值 0 不是失败。
· 基线按类来源选择：MC 类用首次运行时捕获的字节码，自身类可用 jar 版本。
· ForcedClassRewrite 用 Instrumentation.retransformClasses，允许 vanilla。

自检清单（逐条打勾）

· □ 6 处集合是否全部摘除？
· □ 字段名是否用描述符解析？
· □ 返回值 0 是否被当成失败？
· □ 基线是否按类来源选择？
· □ ForcedClassRewrite 是否幂等？
· □ 自身类保护是否跳过合成类？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志无 VerifyError。
· 自检清单逐条打勾。

人类验收

· 6 处索引能全摘。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 7c：Core 层（循环 / 时间 + 隔离 / 审计）

必读原文

· 第七部分 7.4.5–7.4.6 全部
· 第九部分规范 15、16、19

适用规范编号
15、16、19

目标
实现服务端 tick 劫持、独立心跳线程、隔离状态管理、实体列表守护、反射审计。

产出物

· LoopHijacker：TickEvent.ServerTickEvent + LOWEST + END
· HeartbeatThread：ScheduledExecutorService + 只做原子写 + pendingFixup + 50ms + MAX_PRIORITY + daemon
· IsolationManager：PlayerTickEvent + 登出合并清理
· EntityListGuard：每 tick 检查 + 双路径重注入
· ReflectionAudit：-Dfj.reflection.audit=true + 9 个目标类 + [FJ-AUDIT] 格式

关键约束

· LoopHijacker 用 LOWEST + END。
· 心跳线程只做原子写，非原子操作打标记让主线程 drainFixups 消费。
· pendingFixup 是 Map<UUID, Integer>，计数用 merge(uuid, 1, Integer::sum)。
· ReflectionAudit 只在 -Dfj.reflection.audit=true 时调用。

自检清单（逐条打勾）

· □ LoopHijacker 是否用 LOWEST + END？
· □ 心跳线程是否只做原子写？
· □ pendingFixup 是否用 merge 计数？
· □ ReflectionAudit 是否只在系统属性开启时调用？
· □ 隔离状态管理是否在玩家登出时合并清理？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· -Dfj.reflection.audit=true 启动有 [FJ-AUDIT] dump。
· 自检清单逐条打勾。

人类验收

· -Dfj.reflection.audit=true 启动有 [FJ-AUDIT] dump。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 7d：Deception 层（隐藏关键字段）

必读原文：7.17、7.9 扩展 9–11、第九部分规范 33–36
适用规范编号：33、34、35、36

目标
让对方找不到持有者的关键字段，攻击打在诱饵上。

产出物

· DeceptionContext：ThreadLocal 标记
· MixinSynchedEntityDataDeception
· MixinEntityDeception
· MixinEntityRemovalReasonDeception
· MixinLivingEntityDeception
· MixinEntityNBTDeception
· MixinLevelDeception
· DeceptionGuard（Coremod 侧）
· Native 真值存储扩展
· Java 加密 Map 兜底（Native 不可用时）

关键约束

· 真实值只存 Native，Java 层只放诱饵
· Native 不可用时，真实值存 Java 加密 Map
· 按调用者区分返回：自己人读 Native 真值，其他人读诱饵
· 隐藏字段优先级：血量 > removalReason > isRemoved > NBT > UUID 映射
· KillValidator 从 Native 读真值

自检清单（逐条打勾）

· □ 真实值是否只存 Native？
· □ Native 不可用时是否回退 Java 加密 Map？
· □ 调用者区分是否用 ClassLoader + ThreadLocal？
· □ KillValidator 是否从 Native 读真值？
· □ 诱饵值是否互相一致？
· □ 是否影响自己客户端显示？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志无 VerifyError；自检清单逐条打勾。

人类验收

· 对方读血量/removalReason 拿到诱饵；自己人拿到真值。

完成定义

· 验证命令通过；自检清单全部打勾；人类验收通过。

---

Unit 8：Native 桥接完整（主 mod 侧）

必读原文

· 第七部分 7.3 功能 3–9
· 第九部分规范 3、12、17、29、31

适用规范编号
3、12、17、29、31

目标
完成主 mod 侧 Native 桥接：类保护注册、方法级指纹、通用类还原、直杀实体、血量存储桥、RedefineClasses 执行器、第三方类分析。

产出物

· NativeGuard：基线按类来源 + nativeRegisterProtectedClass + 时序
· MethodFingerprint：ASM 提取 + SHA-256 + 忽略 Label/LineNumber/Frame + 跳转标签用首次出现 ID
· ClassRestorer：特征匹配 + 18 项方法名单 + INVOKESPECIAL 调直接父类 + 按返回类型生成 RETURN
· NativeKill：nativeKillEntity(entity, reasonOrdinal) + 5 个枚举序号
· NativeHealthStore：7 个 native 方法 + getHealthForEntity / setHealthForEntity + 未隔离回退
· RedefineExecutor：COMPUTE_FRAMES | COMPUTE_MAXS + 不 SKIP_FRAMES + context classloader + 过滤 net.minecraft.* + getCommonSuperClass 容错
· ThirdPartyAnalyzer：过滤前缀 + ASM 解析 + 可疑度评分

关键约束

· NativeGuard.install 必须在 Agent retransform 和 ForcedClassRewrite 之后。
· RedefineClasses 提交的字节码必须包含完整 StackMapTable。
· Java 侧过滤 vanilla 类：if (className.startsWith("net.minecraft.")) throw skip;
· getCommonSuperClass 用 Thread.currentThread().getContextClassLoader()，失败返回 java/lang/Object，不能抛异常。
· 血量便捷方法签名必须与 ASM invoke 严格一致。

自检清单（逐条打勾）

· □ NativeGuard.install 是否在 Agent retransform 和 ForcedClassRewrite 之后？
· □ RedefineClasses 是否用 COMPUTE_FRAMES | COMPUTE_MAXS？
· □ ClassReader 是否不 SKIP_FRAMES？
· □ 是否过滤了 net.minecraft.*？
· □ getCommonSuperClass 是否用 context classloader？
· □ 血量便捷方法签名是否与 ASM invoke 一致？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志无错误 62。
· 对测试类做 Redefine 无错误 62。
· 自检清单逐条打勾。

人类验收

· 对测试类做 Redefine 无错误 62。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 9a：Attack 层（攻击入口 + 事件触发 + 位置判定）

必读原文

· 第七部分 7.5 功能 1–3
· 第九部分规范 14、32

适用规范编号
14、32

目标
实现攻击入口、事件触发、位置判定。

产出物

· AttackHandler：4 个入口 + 左右键独立冷却 50ms + 检查锚点
· AttackEventHandler：4 个事件 + HIGHEST + receiveCanceled = true + 各自行为
· RayTraceUtil：ServerLevel.getAllEntities + entity.position() + 视线夹角 cos > 0.80 + 5 个常量 + 命中流程

关键约束

· 事件处理器加 HIGHEST + receiveCanceled。
· 不用 isCanceled 检查。
· 位置判定用 entity.position() 而非 getBoundingBox()。
· 常量：MELEE_REACH = 6.0、MAX_COS_ANGLE = 0.80、POSITION_TOLERANCE = 1.5、RANGE_RADIUS = 8.0、REMOTE_DISTANCE = 32.0。

自检清单（逐条打勾）

· □ 事件处理器是否加 HIGHEST + receiveCanceled？
· □ 是否不用 isCanceled 检查？
· □ 位置判定是否用 entity.position()？
· □ 视线夹角是否用 cos > 0.80？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志。
· 自检清单逐条打勾。

人类验收

· 进游戏攻击普通生物，至少一条路径成功。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 9b：Attack 层（击杀链 + 验证 + 压力窗口）

必读原文

· 第七部分 7.5 功能 4–6
· 第九部分规范 8、9、10、16

适用规范编号
8、9、10、16

目标
实现击杀链、验证、压力窗口。

产出物

· KillChain：6 段顺序 + 16 条路径按强度降序 + 每条独立 try-catch + 每条后调 KillValidator.isGone + 前置准备
· KillValidator：6 维位掩码 + 3 步判定顺序 + ALL_PRESENT = 63
· KillPressure：状态机 + 5 tick 稳定 / 60 tick 超时 + rePush 4 项操作

关键约束

· 每条路径独立 try-catch。
· 每条后调 KillValidator.isGone。
· 验证顺序：removalReason != null && isRemoved == true → level.getEntity(id) == null → 未消失。
· 前 3 维全部清除才算消失。
· 压力窗口：STABLE_TICKS = 5，PRESSURE_TICKS = 60。

自检清单（逐条打勾）

· □ 每条路径是否独立 try-catch？
· □ 每条后是否调 KillValidator.isGone？
· □ 验证是否用 6 维位掩码？
· □ 压力窗口状态机是否正确？
· □ rePush 是否包含 4 项操作？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志。
· 自检清单逐条打勾。

人类验收

· 攻击普通生物，至少一条路径成功。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 9c：Attack 层（击杀工具 + 服务端扫描 + 客户端死亡 + 追踪）

必读原文

· 第七部分 7.5 功能 7–10
· 第九部分规范 14、32

适用规范编号
14、32

目标
实现击杀工具、服务端主动扫描、客户端死亡注入、追踪攻击。

产出物

· SilenceKillUtil：委托 KillChain + silencedTargets + 3 条守护逻辑
· ServerAutoAttack：AttackRequestPacket 每 2 tick + 心跳 10 tick + 每 3 tick 主动扫描
· ClientDeathInjection：ClientboundPlayerCombatKillPacket + 拦 ServerboundClientCommandPacket.PERFORM_RESPAWN
· TrackingAttack：连续 5 tick 重定位 + Map<UUID, TrackedTarget>

关键约束

· 心跳超时 10 tick。
· 每次收到攻击请求包或状态包时刷新。
· 追踪表每 tick 递减 remainingTicks。
· TRACK_DURATION_TICKS = 5。

自检清单（逐条打勾）

· □ 攻击请求包处理是否刷心跳？
· □ 攻击状态包的状态机是否正确？
· □ 心跳超时机制是否生效？
· □ 追踪攻击是否连续 5 tick 重定位？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志。
· 自检清单逐条打勾。

人类验收

· 攻击普通生物，至少一条路径成功。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 9d：反传送与追踪

必读原文：7.18、7.9 扩展 12、第九部分规范 37
适用规范编号：37

目标
对方瞬移时仍能命中，持有者被传送/移除时能恢复。

产出物

· TrackingAttack：连续 5 tick 重定位 + Map<UUID, TrackedTarget>
· 反传送位置校验：deltaMovement、chunkPosition、feetBlockState
· 传送后恢复：setPosRaw、setBoundingBox、makeBoundingBox
· 传送后重新追踪：ChunkMap.updatePlayerStatus、TrackedEntity、ServerEntity
· EntityLookup.byUuid / byId

关键约束

· 追踪表每 tick 递减 remainingTicks
· TRACK_DURATION_TICKS = 5
· 位置校验用 entity.position() 而非 getBoundingBox()
· Native 不可用时，位置恢复走 Unsafe + 反射

自检清单（逐条打勾）

· □ 追踪攻击是否连续 5 tick 重定位？
· □ 传送后是否重新追踪？
· □ 位置校验是否用 entity.position()？
· □ deltaMovement 是否检测异常瞬移？
· □ Native 不可用时是否走 Unsafe + 反射回退？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志；自检清单逐条打勾。

人类验收

· 瞬移目标仍能命中；持有者被传送后恢复。

完成定义

· 验证命令通过；自检清单全部打勾；人类验收通过。

---

Unit 10：Defense 层 + 防缴械

必读原文

· 第七部分 7.6 全部 + 7.12 全部
· 第九部分规范 26、27

适用规范编号
26、27

目标
实现伤害拦截、监听器守护、EventBus 结构锚点、背包快照、防缴械。

产出物

· DefenseHandler：LivingHurtEvent / LivingDeathEvent + HIGHEST + 反制哨兵 3 次 / 5000ms
· EventBusGuard：只保护有 @SubscribeEvent 的类 + 6 个保护列表 + 60 秒警告限流
· EventBusAnchor：保存 listeners 引用 + 每 tick 检查 + 不做内容对比
· InventorySnapshot：每 1 秒 + 主背包 36 格 + 全空则恢复
· AntiDisarm：锚点槽位追踪表 + 允许丢弃表 + 槽位号扩展 + 5 个入口 + setItem 拦截 + 恢复逻辑 + 追踪表更新 + 命令拦截 + 特殊场景处理

关键约束

· 只保护有 @SubscribeEvent 的类。
· 反制哨兵参数：COUNTER_ATTACK_THRESHOLD = 3，COUNTER_ATTACK_WINDOW_MS = 5000。
· 追踪表覆盖主背包 0–35、副手 -1、盔甲 -2 至 -5。
· setItem 拦截用时间窗口 + 调用栈白名单双重确认。
· 允许丢弃表跨 tick。
· 命令拦截：/clear、/data merge entity、/item replace。

自检清单（逐条打勾）

· □ 追踪表覆盖了主背包 + 副手 + 盔甲槽吗？
· □ 主动丢弃的识别点有几个？都覆盖了网络包入口吗？
· □ setItem 拦截有没有调用栈检查？
· □ 允许丢弃表是跨 tick 的还是单 tick 的？
· □ 追踪表在玩家登出时清理了吗？
· □ 箱子、潜影盒、铁砧、创造模式的操作会不会被误拦？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志。
· 自检清单逐条打勾。

人类验收

· 持有者免疫伤害。
· 主动丢弃放行，被动移除恢复。
· 副手武器保护。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 10b：动态生成类与隐藏类对抗

必读原文：7.19、5.4 扩展 3–4、6.5 扩展 5–6、第九部分规范 38
适用规范编号：38

目标
识别、捕获、分析运行时动态生成的类与隐藏类。

产出物

· Native ClassFileLoadHook 记录所有类加载事件
· GetLoadedClasses 定期枚举
· 行为分析替代静态分析
· Agent 定时 retransform 扫描动态类
· Coremod 通配符 targets

关键约束

· 隐藏类可能不被 GetLoadedClasses 返回
· 动态生成类不能被 retransformClasses 重定义
· 行为分析不依赖静态分析
· Native 不可用时，走 Agent ClassFileTransformer 回退

自检清单（逐条打勾）

· □ ClassFileLoadHook 是否记录动态生成类？
· □ GetLoadedClasses 是否定期枚举？
· □ 是否用行为分析替代静态分析？
· □ Agent 是否定时扫描动态类？
· □ Native 不可用时是否走 Agent 回退？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志；自检清单逐条打勾。

人类验收

· 动态生成类被记录；隐藏类被检测。

完成定义

· 验证命令通过；自检清单全部打勾；人类验收通过。

---

Unit 11a：武器 + 网络

必读原文

· 第七部分 7.10 + 7.11
· 第九部分规范 4、32

适用规范编号
4、32

目标
实现武器物品和网络层。

产出物

· FinalJudgmentItem：继承 SwordItem + stacksTo(1) + fireResistant + 攻击 +3 / 速度 -2.4 + hurtEnemy 只处理 ServerPlayer + use() 返回 pass() + inventoryTick 服务端重写锚点 + appendHoverText 显示四层状态 + isFoil 返回 true + 自定义 Tier
· NetworkHandler：协议版本 "1" + 通道 ResourceLocation(modid, "main") + 包 ID 0 攻击请求 / 包 ID 1 攻击状态 + 攻击请求包处理顺序 4 步 + 攻击状态包状态机 + 心跳超时 10 tick + 构造器阶段注册

关键约束

· use() 必须返回 InteractionResultHolder.pass()。
· hurtEnemy 只处理 ServerPlayer。
· inventoryTick 只在服务端重写锚点。
· 网络包必须在构造器阶段注册。
· 攻击请求包处理：刷心跳 → 检查锚点 → 射线检测 → 触发近战。

自检清单（逐条打勾）

· □ 武器 use() 是否返回 pass()？
· □ hurtEnemy 是否只处理 ServerPlayer？
· □ inventoryTick 是否只在服务端重写锚点？
· □ appendHoverText 是否显示四层状态？
· □ 网络包是否在构造器阶段注册？
· □ 攻击请求包处理是否刷心跳？
· □ 攻击状态包的状态机是否正确？
· □ 心跳超时机制是否生效？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志。
· 自检清单逐条打勾。

人类验收

· 武器 tooltip 显示四层状态。
· 网络包正常收发。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 11b：Client + Mixin

必读原文

· 第七部分 7.7 + 7.8
· 第九部分规范 25、32

适用规范编号
25、32

目标
实现 Client 层左键检测、渲染保护、Mixin 配置、Accessor/Invoker、方法注入。

产出物

· MixinMinecraftAttackTrigger：状态机 + getGameTime 去重 + REPEAT_INTERVAL 2 / REPEAT_TIMEOUT 40
· RenderProtector
· finaljudgment.mixins.json：16 common + 4 client + injectors.defaultRequire = 1
· build.gradle mixin 配置
· Accessor / Invoker：Entity.removalReason / setRemoved / LivingEntity.dead / SynchedEntityData.itemsById / DataItem.value
· 方法注入 22 项（完整描述符表）
· 所有注入方法加 @SuppressWarnings("mapping")

关键约束

· Minecraft 类仅客户端。
· mixins.json 的 package 路径与类实际包名一致。
· injectors.defaultRequire = 1。
· 方法注入描述符必须与 7.8 完整描述符表一致。

自检清单（逐条打勾）

· □ mixins.json 是否列全？与项目里的 @Mixin 类数量对比。
· □ build.gradle 是否配置 mixin？
· □ 所有注入方法是否加 @SuppressWarnings("mapping")？
· □ Minecraft 类是否仅客户端？
· □ 左键检测状态机是否正确？
· □ 渲染保护是否生效？

验证命令

```bash
gradlew.bat runClient
```

证据要求

· 启动日志。
· Mixin 全部注入成功。
· 自检清单逐条打勾。

人类验收

· 左键检测生效。
· Mixin 全部注入成功。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

---

Unit 12：联调与扩展

必读原文

· 6.5 + 7.9 + 7.13 + 第十一部分 + 第十二部分 + 第十三部分
· 第九部分全部 53 条规范

适用规范编号
全部 53 条

目标
四层状态验证、攻击功能验证、防御功能验证、防缴械验证、日志验收、人工抽查、生产环境验证、交付物检查。

产出物

· 联调报告
· 生产环境启动验证
· 交付物清单

关键约束

· 关掉某层其他层仍工作。
· 日志无三大 Error。
· 人工抽查 12 项。
· 7.13 现象 → 根因对照 7 项作为调试参考。
· 生产环境：两个 jar 放进 mods/，启动器启动。

自检清单（逐条打勾）

· □ 四层状态验证：tooltip 显示 Native / Agent / Coremod / Bootstrap；关掉某层其他层仍工作。
· □ 攻击功能验证：普通生物 / 防御型实体 / 瞬移目标。
· □ 防御功能验证：免疫伤害 / 反制哨兵 / 监听器守护 / 背包快照。
· □ 防缴械验证：主动丢弃放行 / 被动移除恢复 / 副手保护 / 命令拦截。
· □ 日志验收 5 项：VerifyError / NoSuchFieldError / NoClassDefFoundError / [FJ] 日志 / 无刷屏。
· □ 人工抽查 12 项。
· □ 生产环境验证：两个 jar 放进 mods/，启动器启动。
· □ 交付物检查 6 项。

验证命令

```bash
gradlew.bat runClient
# 生产环境：启动器启动
```

证据要求

· 全部 Phase 出口检查通过。
· 日志片段。
· 自检清单逐条打勾。

人类验收

· 进游戏完整验证。
· 生产环境可启动。

完成定义

· 验证命令通过。
· 自检清单全部打勾。
· 人类验收通过。

可选加强（联调通过后再考虑）

· 5.4 扩展 1：通用防守检测
· 5.4 扩展 2：目标类通用守卫
· 6.5 扩展 1：多平台 Native
· 6.5 扩展 2：方法级指纹保护
· 6.5 扩展 3：对方类重写
· 6.5 扩展 4：对方 native 反制
· 7.9 扩展 1：多平台 Native
· 7.9 扩展 2：反射字段审计工具
· 7.9 扩展 3：四层状态可视化
· 7.9 扩展 4：通用防御类还原
· 7.9 扩展 5：对抗 native 反制
· 7.9 扩展 6：动态 API 适配
· 7.9 扩展 7：我方攻击手段
· 7.9 扩展 8：我方防御手段
· 7.9 扩展 9：隐藏关键字段（Deception 层）
· 7.9 扩展 10：欺骗进攻成功
· 7.9 扩展 11：让对方无法选中持有者
· 7.9 扩展 12：反传送机制
· 7.9 扩展 13：动态生成类与隐藏类对抗
· 7.9 扩展 14：ECA 数据流改血反制
· 7.9 扩展 15：分层降级与回退路径
· 7.9 扩展 16：降级路径的验证

补充 Unit（已并入 10.3 工作单元）

· Unit 0b：降级路径验证
· Unit 7d：Deception 层（隐藏关键字段）
· Unit 9d：反传送与追踪
· Unit 10b：动态生成类与隐藏类对抗

---

10.4 文档 → Unit 对照表（细化到功能点）

文档章节 具体内容 Unit
1.1 项目目标 秒杀武器 全程
1.2 为什么需要多层 多层纵深 全程
1.3 六大设计原则 原则 1–6 + 补充原则 7–12 全程
2.1 五类产物 主 mod / Coremod / Agent / Native / Bootstrap 0、12
2.2 用户安装 两个 jar 放 mods/ 0、12
2.3 Coremod 独立 jar -transformer 后缀 + FMLModType + services 5
2.3 Agent 嵌入主 mod MANIFEST 三件套 + Can-* 3、6
2.3 Native 嵌入主 mod 平台检测 + 提取 2、6
2.3 Bootstrap 嵌入主 mod 独立 submodule + 常量 4、6
2.4 各产物 ClassLoader 加载器对照 全程
3.1 功能目标 共用契约 1
3.2 功能 1 SRG 名集中管理 1
3.2 功能 2 全局常量 1
3.2 功能 3 状态接口 1
3.2 功能 4 KillFlag 1
3.3 关键技术点 SRG 对照表 + 开发/生产差异 1
3.4 自检清单 6 项 1
4.1 功能目标 Agent 层目标 3
4.2 功能 1 跨 ClassLoader 传递 Instrumentation 3
4.2 功能 2 动态附加 3、6
4.2 功能 3 定时校验 3、6
4.2 功能 4 ForcedClassRewrite 3、6
4.3 关键技术点 子 JVM / MANIFEST / 我方标记 / 轮询 / context CL / COMPUTE_FRAMES 3
4.4 自检清单 6 项 3
5.1 功能目标 Coremod 层目标 5
5.2 功能 1 服务注册 5
5.2 功能 2 目标类字节码注入 5
5.2 功能 3 守卫注入 5
5.2 功能 4 CoremodGuard 5
5.2 功能 5 KillRegistry 5
5.2 功能 6 CoremodKillTicker 5
5.3 关键技术点 描述符解析 / 纯字节码 / -transformer / 独立 jar / onLoad / 反射跨 CL / Minecraft 分侧 / Forge 扩展 5
5.4 扩展 1 通用防守检测 12 可选
5.4 扩展 2 目标类通用守卫 12 可选
5.5 自检清单 6 项 5
6.1 功能目标 Native 层目标 2
6.2 功能 0 JNI 生命周期 2
6.2 功能 1 JVMTI 环境获取 2
6.2 功能 2 能力申请 2
6.2 功能 3 ClassFileLoadHook 2
6.2 功能 4 RedefineClasses 执行 2、8
6.2 功能 5 运行时字节码捕获 2
6.2 功能 6 类保护表 2、8
6.2 功能 7 直杀实体 2、8
6.2 功能 8 血量 Native 存储 2、8
6.2 功能 9 日志桥接 2、6
6.2 功能 10 Bootstrap 注入 2、4
6.2 功能 11 类枚举 / 常量池 2
6.2 功能 12 我方 retransform 标记 2、3
6.2 功能 13 Native 自检工具链 2
6.3 关键技术点 JNI_OnLoad / 4 段 hook / jvmtiClassDefinition / COMPUTE_FRAMES / 运行时捕获 / 我方标记 / 日志桥接 / 跨平台锁 / 签名严格 / Bootstrap 限制 2
6.4 JVMTI 错误码速查 62 / 66 / 67 / 68 / 99 / 103 2、8
6.5 扩展 1 多平台 Native 12 可选
6.5 扩展 2 方法级指纹保护 12 可选
6.5 扩展 3 对方类重写 12 可选
6.5 扩展 4 对方 native 反制 12 可选
7.1 功能 1 主入口 6
7.1 功能 2 初始化调度 6
7.1 功能 3 状态查询 6
7.2 功能 1 反射读 Instrumentation 6
7.2 功能 2 动态附加 6
7.2 功能 3 定时 retransform 6
7.3 功能 1 JNI 声明与加载 6
7.3 功能 2 日志接收 6
7.3 功能 3 类保护注册 8
7.3 功能 4 方法级指纹 8
7.3 功能 5 通用类还原 8
7.3 功能 6 直杀实体 8
7.3 功能 7 血量存储桥 8
7.3 功能 8 RedefineClasses 执行器 8
7.3 功能 9 第三方类分析 8
7.4.1 功能 1 Unsafe 访问 7a
7.4.1 功能 2 字段直写 7a
7.4.1 功能 3 血量字段读写 7a
7.4.1 功能 4 SynchedEntityData 内部直写 7a
7.4.2 功能 1 影子血量 7a
7.4.2 功能 2 武器锚点 7a
7.4.3 功能 1 从 jar 读原始字节码 7b
7.4.3 功能 2 ASM 方法体改写 7b
7.4.3 功能 3 主 Transformer 7b
7.4.3 功能 4 自身类保护 7b
7.4.3 功能 5 ForcedClassRewrite 7b
7.4.4 功能 1 6 处集合摘除 7b
7.4.4 功能 2 深度扫容器 7b
7.4.4 功能 3 实体重注入 7b
7.4.5 功能 1 服务端 tick 劫持 7c
7.4.5 功能 2 独立心跳线程 7c
7.4.6 功能 1 隔离状态管理 7c
7.4.6 功能 2 实体列表守护 7c
7.4.6 功能 3 反射审计 7c
7.5 功能 1 攻击入口 9a
7.5 功能 2 事件触发 9a
7.5 功能 3 位置判定 9a
7.5 功能 4 击杀链 9b
7.5 功能 5 验证 9b
7.5 功能 6 压力窗口 9b
7.5 功能 7 击杀工具 9c
7.5 功能 8 服务端主动扫描 9c
7.5 功能 9 客户端死亡注入 9c
7.5 功能 10 追踪攻击 9c
7.6 功能 1 伤害拦截 10
7.6 功能 2 监听器守护 10
7.6 功能 3 EventBus 结构锚点 10
7.6 功能 4 背包快照 10
7.7 功能 1 左键检测 11b
7.7 功能 2 渲染保护 11b
7.8 功能 0 Mixin 配置 11b
7.8 功能 1 Accessor / Invoker 11b
7.8 功能 2 方法注入 22 项 11b
7.8 功能 2 补充 完整描述符表 11b
7.9 扩展 1–6 加强拓展 12 可选
7.9 扩展 7 我方攻击手段 9b、9c、10b
7.9 扩展 8 我方防御手段 全程
7.9 扩展 9 隐藏关键字段（Deception 层） 7d
7.9 扩展 10 欺骗进攻成功 7d
7.9 扩展 11 让对方无法选中持有者 7d、9d
7.9 扩展 12 反传送机制 9d
7.9 扩展 13 动态生成类与隐藏类对抗 10b
7.9 扩展 14 ECA 数据流改血反制 7d、9b
7.9 扩展 15 分层降级与回退路径 0b、全程
7.9 扩展 16 降级路径的验证 0b、12
7.15 我方攻击手段总表 攻击 9b、9c、10b
7.16 我方防御手段总表 防御 全程
7.17 隐藏关键字段 Deception 层 7d
7.18 反传送机制 反传送 9d
7.19 动态生成类与隐藏类对抗 动态类 10b
7.20 ECA 数据流改血反制 ECA 反制 7d、9b
7.21 分层降级与回退路径 回退 0b、全程
7.10 武器物品 武器实现 11a
7.11 网络层 网络实现 11a
7.12 防缴械 全部 10
7.13 现象 → 根因对照 7 项 12 参考
7.14 常量表 24 项 1
第八部分 初始化顺序 6
第九部分 53 条规范 全程遵循
第十一部分 交付物 12
第十二部分 验收动作 全程 + 12
第十三部分 人工抽查 12

---

10.5 硬约束速查（全量 53 条规范映射表）

编号 规范简述 适用 Unit
1 SRG 名集中管理 1、5、7、8
2 Agent ASM 用 COMPUTE_FRAMES \| COMPUTE_MAXS，重写 getCommonSuperClass 3、7、8
3 Native RedefineClasses 用 COMPUTE_FRAMES \| COMPUTE_MAXS，ClassReader 不 SKIP_FRAMES 2、8
4 武器 use() 返回 pass() 11a
5 Coremod 独立 jar + -transformer，MANIFEST 用 FMLModType，mods.toml 无 modType 5
6 RedefineClasses 用 jvmtiClassDefinition 2、8
7 血量 Native 优先 7a、8
8 KillValidator 综合判断，不只看 removalReason 9b
9 KillChain 每条路径独立 try-catch 9b
10 同一方法只由一层负责 全程
11 Coremod 字段名用描述符解析 5
12 nativeSetSelfRetransform 用计数器而非布尔 2、3、6
13 反射字段名用集中管理表 1、7、8
14 事件处理器加 HIGHEST + receiveCanceled 9a
15 LoopHijacker 用 LOWEST + END 7c
16 心跳线程只做原子写 7c
17 Native 重定义跳过 net.minecraft.* 2、8
18 FieldIsolation.isIsolated 加 instanceof Player 7a
19 ReflectionAudit 只在 -Dfj.reflection.audit=true 时调用 7c
20 Coremod 与主模块通信走 KillRegistry 反射，不打包 common 类 5
21 跨 ClassLoader 静态字段不共享，KillFlag 必须唯一加载且位于 game 层（coremod） 1、5
22 Agent 动态附加依赖 jdk.attach；标准 JDK 17 默认包含 3、6
23 Bootstrap 类不能引用 MC / mod 类 4
24 守卫注入按返回类型生成 return 5
25 Minecraft 类仅客户端，Coremod 分侧 5、11b
26 防缴械用“允许丢弃表”区分主动和被动 10
27 锚点槽位追踪扩展副手 + 盔甲槽 10
28 coremod / agent / common 用 compileOnly ASM 时，只使用 ASM 9.5 已存在的 API 1、3、5、8
29 NativeGuard.install 必须在 Agent retransform 和 ForcedClassRewrite 之后 6、8
30 禁止使用 Field.setAccessible(true) 访问 java.base 私有成员；改用 Unsafe 直写 6、7、8
31 ForcedClassRewrite 用 Instrumentation.retransformClasses；Native Redefine 用 JVMTI 2、3、8
32 日志前缀统一：主 mod [FJ]，Native [FJ-Native]，Coremod [FJ-Coremod]，Agent [FJ-Agent]，Bootstrap [FJ-Bootstrap] 全程
33 Deception 层真实值只存 Native，Java 层只放诱饵；按调用者区分返回 对方读到诱饵，自己人读真值
34 KillValidator 从 Native 读真值，不依赖 getHealth / isRemoved 方法返回值 反制“所有方法返回 TRUE”与 ECA 字节码逆向
35 隐藏字段优先级：血量 > removalReason > isRemoved > NBT > UUID 映射 逐步覆盖对方判死逻辑
36 调用者区分用 ClassLoader + ThreadLocal，自己代码调用前设标记 误判导致自己人拿到诱饵
37 反传送：追踪攻击连续 5 tick 重定位 + 位置校验 deltaMovement / chunkPosition 对方瞬移后仍能命中
38 动态生成类与隐藏类：ClassFileLoadHook 记录 + 行为分析替代静态分析 静态分析失效时仍能识别
39 每层必须有独立降级路径，Native 不是唯一依赖 单层失败全盘瘫痪
40 Native 加载失败不抛异常，打 WARN 日志，nativeReady = false 阻塞其他层初始化
41 关键路径至少三条独立实现：Native / Unsafe / Mixin 一条失败全链中断
42 血量存储降级：Native → Java 加密 Map → 内存混淆 → 原版 反编译工具能扫到明文
43 类保护降级：NativeGuard → Agent SelfClassProtector → Coremod 守卫 → 方法级指纹 类被篡改无法检测
44 我方 retransform 标记降级：Native 计数器 → Bootstrap AtomicInteger → 系统属性 → ThreadLocal 跨 CL 标记丢失
45 Deception 真值存储降级：Native → Java 加密 Map → 内存混淆 → 不做 Deception Native 不可用时真值暴露
46 每层独立启动检查，任一层失败不影响其他层 初始化顺序错误导致连锁失败
47 武器 tooltip 显示四层状态，玩家能看出哪层降级 降级不可见
48 攻击手段必须从我方视角实现，不依赖对方是否存在 只能防守不能反击
49 AllReturn 只对目标实体所属模组包生效，不波及 MC/Forge 避免影响原版逻辑
50 方法体清空/INVOKESPECIAL 改写只对第三方防御类生效，跳过 net.minecraft.* 误伤原版实体
51 EntityDataSweep / NBT 清洗只在 KillChain 触发时执行，不每 tick 扫 性能开销
52 清空 EventBus / 替换 listeners 引用只在自愈路径中使用，不主动破坏对方 误伤自己监听器
53 我方攻击手段同样需要 try-catch + KillValidator 校验 攻击失败不阻塞后续路径

---

10.6 人类介入节点

· 前置准备完成：确认 gradlew build 通过。
· 每个 Unit 设计输出后：确认类结构，并检查设计是否引用原文小节和规范编号。
· Unit 0b：关掉 Native / Agent / Coremod / Mixin，验证其他层仍能工作。
· Unit 1：看 SRG 名是否集中。
· Unit 2：确认 dll 路径。
· Unit 3：解压看 MANIFEST。
· Unit 4：javap 检查无 MC 引用。
· Unit 5：解压检查 5 项结构。
· Unit 6：runClient 启动。
· Unit 7a/7b/7c：runClient 启动无 VerifyError。
· Unit 7d：对方读血量/removalReason 拿到诱饵，自己人拿到真值。
· Unit 8：runClient 启动无错误 62。
· Unit 9a/9b/9c：进游戏测。
· Unit 9d：瞬移目标仍能命中，持有者被传送后恢复。
· Unit 10：进游戏测防御 + 防缴械。
· Unit 10b：动态生成类被记录，隐藏类被检测。
· Unit 11a/11b：进游戏测武器 + 网络 + Mixin。
· Unit 12：全部验证 + 生产环境启动。

其他节点收到“确认，继续”或“Unit X 通过，开始 Unit X+1”即可继续。

---

10.7 暂停信号

· 一次改了 3 个以上 Unit。
· 开始改 IMPLEMENTATION_SPEC.md 或 ENV_SPEC.md。
· 构建失败连续 5 次无进展。
· 日志里出现 legacy 引用。
· 自行加了规范里没有的模块。
· 把 modType 又写回 mods.toml。
· 主 jar 里没嵌入 agent / native / bootstrap。
· 开始“优化”已通过的代码。
· 跳过验证直接进下一步。
· 自检清单未逐条打勾。
· 未提供日志证据。
· 设计未引用原文小节和规范编号。
· 把大 Unit 合并执行。
· 跳过人类验收直接进下一步。
· 未验证降级路径就继续。
· Native 加载失败时未打 WARN 日志。
· 武器 tooltip 未显示四层状态。
· 关键路径只有 Native 一条实现。
· Deception 真值未存 Native 或 Java 加密 Map。
· 反传送未做 5 tick 重定位。
· 动态生成类未被 ClassFileLoadHook 记录。

第十部分结束。

---

第十一部分：交付物

产物 说明
源码仓库 6 个模块（common / coremod / agent / native / bootstrap / main）
finaljudgment.jar 主 mod
finaljudgment-coremod-transformer.jar Coremod
finaljudgment-agent.jar Agent（嵌入主 mod）
finaljudgment_native.dll Native（嵌入主 mod）
finaljudgment-bootstrap.jar Bootstrap（嵌入主 mod）
分发说明 两个 jar 都放 mods/

---

第十二部分：每轮 AI 迭代的验收动作

每轮结束时，只看日志，不看代码：

# 检查 期望
1 搜 latest.log 里的 VerifyError 无
2 搜 NoSuchFieldError 无
3 搜 NoClassDefFoundError 无
4 搜 [FJ] 有本模块的启动日志 是
5 功能测试 按 Phase 的验收标准

日志前缀规范：

· 主 mod：[FJ]
· Native：[FJ-Native]
· Coremod：[FJ-Coremod]
· Agent：[FJ-Agent]
· Bootstrap：[FJ-Bootstrap]

---

第十三部分：人工抽查点（AI 不需要主动执行，但不得违反）

不需要逐行看代码。只检查这几项：

检查项 怎么查
多层是否独立 关掉 Native，Agent 是否还工作
每层是否有降级日志 搜 失败 / 降级 / skip
SRG 名是否全集中 搜 f_ 开头的字符串是否只出现在映射表
跨 ClassLoader 是否用反射 看 Coremod 引用的主模块类
RedefineClasses 是否用 COMPUTE_FRAMES 搜 ClassWriter 参数
KillChain 每条是否 try-catch 看 for 循环体
武器是否 pass() 搜 use( 返回值
mixins.json 是否列全 与项目里的 @Mixin 类数量对比
启动无异常 latest.log 无三大 Error
防缴械是否实现 搜 allowDiscard / trackedSlots
NativeGuard 与 Agent 时序 检查 NativeGuard.install 调用点是否在 Agent retransform 之后
是否使用 setAccessible 访问 java.base 搜 setAccessible

---

文档结束。