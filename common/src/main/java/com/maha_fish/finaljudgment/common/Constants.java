package com.maha_fish.finaljudgment.common;

import java.util.function.Consumer;

/**
 * 全局常量：MOD_ID、锚点 key/value、系统属性名、资源路径、7.14 常量表、
 * 以及 Unit 0b 冻结的日志 / tooltip / 降级链契约。
 *
 * <p>对应 IMPLEMENTATION_SPEC 3.2 功能 2（全局常量）、7.14（常量表 24 项）、
 * 7.21（分层降级总表）与 Unit 0b 冻结契约。</p>
 *
 * <p>跨 ClassLoader 契约：与 {@link SRGNames} 相同，字段名视为稳定公共 API，
 * Coremod 只能经目标类 ClassLoader 反射读取。Bootstrap 侧的<b>同值副本</b>由 Unit 4 建立，
 * 且必须放在<b>不匹配 {@code com.maha_fish.finaljudgment.*} 的包</b>（ENV_SPEC 4.5 禁止
 * Bootstrap 引用本前缀），本类不依赖它。</p>
 */
public final class Constants {

    private Constants() {
    }

    // ===================== 3.2 功能 2：基础常量 =====================

    /** mod id（与 mods.toml / gradle.properties 的 mod_id 一致） */
    public static final String MOD_ID = "finaljudgment";

    /** 锚点物品 NBT 键（7.4.2 功能 2） */
    public static final String ANCHOR_NBT_KEY = "final_judgment_core";

    /** 锚点物品 NBT 值（7.4.2 功能 2） */
    public static final String ANCHOR_NBT_VALUE = "v1";

    /** Coremod 已加载标志（5.2 功能 1） */
    public static final String SYS_COREMOD_LOADED = "fj.coremod.loaded";

    /** Bootstrap 已注入标志（7.1 功能 3 / Unit 0b 四层查询） */
    public static final String SYS_BOOTSTRAP_LOADED = "fj.bootstrap.loaded";

    /** 反射审计开关（7.4.6 功能 3，规范 19） */
    public static final String SYS_REFLECTION_AUDIT = "fj.reflection.audit";

    /** 我方 retransform 标记的系统属性级载体（规范 44；与 bootstrap Constants 同值） */
    public static final String SYS_SELF_RETRANSFORM = "fj.self.retransform";

    /** 内嵌 Agent jar 的资源路径（ENV_SPEC 4.3 / 7.2 功能 2） */
    public static final String RES_AGENT_JAR = "/META-INF/agent/finaljudgment-agent.jar";

    /** 内嵌 Bootstrap jar 的资源路径（ENV_SPEC 4.5 / 6.2 功能 10） */
    public static final String RES_BOOTSTRAP_JAR = "/META-INF/bootstrap/finaljudgment-bootstrap.jar";

    /** 内嵌 Native 库的根路径（ENV_SPEC 4.4） */
    public static final String RES_NATIVE_ROOT = "/META-INF/native/";

    /** 本项目唯一支持的平台目录名（ENV_SPEC 一） */
    public static final String NATIVE_PLATFORM_DIR = "windows-x86_64";

    /** Windows x86_64 原生库文件名（ENV_SPEC 4.4，无 lib 前缀） */
    public static final String NATIVE_LIB_NAME = "finaljudgment_native.dll";

    // ===================== 7.14 常量表（24 项） =====================

    /** 攻击冷却（ms） */
    public static final long ATTACK_COOLDOWN_MS = 50L;

    /** 范围攻击半径 */
    public static final double RANGE_RADIUS = 10.0D;

    /** 远程攻击距离（修订：32 → 64） */
    public static final double REMOTE_DISTANCE = 64.0D;

    /** 近战射线距离 */
    public static final double MELEE_REACH = 6.0D;

    /** 视线夹角余弦阈值 */
    public static final double MAX_COS_ANGLE = 0.80D;

    /** 位置容差 */
    public static final double POSITION_TOLERANCE = 1.5D;

    /** 反制哨兵触发次数 */
    public static final int COUNTER_ATTACK_THRESHOLD = 3;

    /** 反制哨兵窗口（ms） */
    public static final long COUNTER_ATTACK_WINDOW_MS = 5000L;

    /** 服务端主动扫描间隔（tick） */
    public static final int SCAN_INTERVAL_TICKS = 3;

    /** 服务端攻击心跳超时（tick） */
    public static final int ATTACK_STATE_TIMEOUT_TICKS = 10;

    /** 客户端重复发包间隔（tick） */
    public static final int REPEAT_INTERVAL = 2;

    /** 客户端持续按住超时（tick） */
    public static final int REPEAT_TIMEOUT = 40;

    /** 背包快照间隔（ms） */
    public static final long SNAPSHOT_INTERVAL_MS = 1000L;

    /** 警告限流（ms，规范 10） */
    public static final long WARN_COOLDOWN_MS = 60000L;

    /** 定时 retransform 间隔（秒，4.2 功能 3） */
    public static final int SCAN_INTERVAL_SECONDS = 5;

    /** KillRegistry 标记超时（ms，5.2 功能 5） */
    public static final long MARK_TIMEOUT_MS = 30000L;

    /** 压力窗口超时（tick，7.5 功能 6） */
    public static final int PRESSURE_TICKS = 60;

    /** 压力窗口稳定判定（tick，7.5 功能 6） */
    public static final int STABLE_TICKS = 5;

    /** 追踪攻击时长（tick，7.5 功能 10） */
    public static final int TRACK_DURATION_TICKS = 5;

    /** 1.20.1 的 DATA_HEALTH_ID（7.4.1 功能 3） */
    public static final int HEALTH_DATA_ID = 9;

    /** Entity.RemovalReason.KILLED 的 ordinal（7.3 功能 6） */
    public static final int REASON_KILLED = 0;

    /** 类保护表上限（6.2 功能 0） */
    public static final int MAX_PROTECTED_CLASSES = 64;

    /** 容器深度扫描上限（7.4.4 功能 2） */
    public static final int MAX_DEPTH = 5;

    /** 心跳周期（ms，7.4.5 功能 2） */
    public static final long HEARTBEAT_INTERVAL_MS = 50L;

    // ===================== Unit 0b 契约：层名与日志前缀（规范 32） =====================

    /** 主 mod 日志前缀 */
    public static final String LOG_PREFIX_MAIN = "[FJ]";

    /** Native 层日志前缀 */
    public static final String LOG_PREFIX_NATIVE = "[FJ-Native]";

    /** Coremod 层日志前缀 */
    public static final String LOG_PREFIX_COREMOD = "[FJ-Coremod]";

    /** Agent 层日志前缀 */
    public static final String LOG_PREFIX_AGENT = "[FJ-Agent]";

    /** Bootstrap 层日志前缀 */
    public static final String LOG_PREFIX_BOOTSTRAP = "[FJ-Bootstrap]";

    /** 层名：Native */
    public static final String LAYER_NATIVE = "Native";

    /** 层名：Agent */
    public static final String LAYER_AGENT = "Agent";

    /** 层名：Coremod */
    public static final String LAYER_COREMOD = "Coremod";

    /** 层名：Bootstrap */
    public static final String LAYER_BOOTSTRAP = "Bootstrap";

    /** 四层固定顺序（tooltip 与启动汇总必须按此顺序，不得换序） */
    public static final String[] LAYER_ORDER = {LAYER_NATIVE, LAYER_AGENT, LAYER_COREMOD, LAYER_BOOTSTRAP};

    // ===================== Unit 0b 契约：日志模板（body，不含前缀） =====================

    /** 启动四层汇总。输出行 = 前缀 + 空格 + body。 */
    public static final String LOG_LAYER_READY_TEMPLATE = "层就绪: Native=%s Agent=%s Coremod=%s Bootstrap=%s";

    /** 单层未就绪（WARN，必须打，规范 40）。参数：层名、原因 */
    public static final String LOG_LAYER_NOT_READY_TEMPLATE = "层未就绪: %s (原因: %s)";

    /** 降级（INFO）。参数：能力、首选来源、当前来源、原因 */
    public static final String LOG_DEGRADE_TEMPLATE = "%s 降级: %s → %s (原因: %s)";

    /** 恢复（INFO）。参数：能力、当前来源、首选来源 */
    public static final String LOG_RECOVER_TEMPLATE = "%s 恢复: %s → %s";

    /** 回退链耗尽（WARN）。参数：能力 */
    public static final String LOG_UNAVAILABLE_TEMPLATE = "%s 不可用: 全部回退链失败";

    /** Agent 探查失败（DEBUG，7.2 功能 1）。参数：原因 */
    public static final String LOG_AGENT_PROBE_FAILED_TEMPLATE = "Agent 探查失败: %s";

    /** 原文逐字示例（Unit 0b / 7.21）：打上主 mod 前缀后即 {@code [FJ] Native 不可用，回退到 UnsafeWrite}。 */
    public static final String LOG_NATIVE_FALLBACK_EXAMPLE = "Native 不可用，回退到 UnsafeWrite";

    // ===================== Unit 0b 契约：未就绪原因枚举 =====================

    /** 原因：提取 dll 失败 */
    public static final String REASON_EXTRACT_DLL = "提取 dll 失败";

    /** 原因：System.load 失败 */
    public static final String REASON_SYSTEM_LOAD = "System.load 失败";

    /** 原因：探针无响应 */
    public static final String REASON_PROBE = "探针无响应";

    /** 原因：jdk.attach 不可用 */
    public static final String REASON_NO_ATTACH = "jdk.attach 不可用";

    /** 原因：注入未执行 */
    public static final String REASON_INJECT_NOT_RUN = "注入未执行";

    // ===================== Unit 0b 契约：降级链（7.21 总表，11 项能力 × 5 列） =====================

    /**
     * 分层降级总表（7.21），只读常量表。
     *
     * <p>每行 5 列：{@code [0]} 能力名、{@code [1]} 首选、{@code [2]} 回退 1、
     * {@code [3]} 回退 2、{@code [4]} 回退 3。来源标识字符串<b>逐字取自 7.21 原文</b>，
     * 禁止各层自造名称。能力名即「能力枚举」，因此不再单独维护一份名字数组。</p>
     */
    public static final String[][] DEGRADE_CHAINS = {
            {"直杀实体", "NativeKill", "UnsafeWrite", "MixinAccessorWrite", "VanillaSetRemoved"},
            {"改方法体", "NativeRedefineAll", "Agent ASM", "Coremod 守卫", "Mixin 注入"},
            {"集合摘除", "Native 集合操作", "Unsafe 读字段 + remove", "反射读字段 + remove", "MapSweep"},
            {"血量存储", "Native HealthEntry", "Java 加密 Map", "内存混淆 Map", "原版 DATA_HEALTH_ID"},
            {"类保护", "NativeGuard", "Agent SelfClassProtector", "Coremod 守卫", "方法级指纹"},
            {"我方标记", "Native 计数器", "Bootstrap AtomicInteger", "系统属性", "ThreadLocal"},
            {"日志桥接", "Native JNI 回调", "Bootstrap 共享日志类", "系统属性", "直接 Java 日志"},
            {"Deception 真值", "Native HealthEntry", "Java 加密 Map", "内存混淆", "不做 Deception"},
            {"动态类检测", "Native ClassFileLoadHook", "Agent ClassFileTransformer", "Coremod 通配符", "行为分析"},
            {"类枚举", "Native GetLoadedClasses", "Instrumentation.getAllLoadedClasses", "ClassLoader 递归", "不做枚举"},
            {"字节码捕获", "Native RetransformClasses", "Instrumentation.retransformClasses",
                    "Agent ClassFileTransformer", "从 jar 读原始字节"},
    };

    // ===================== Unit 0b 契约：tooltip（方案 B 单行） =====================

    /** 就绪标记 */
    public static final String TOOLTIP_READY = "§a✓";

    /** 未就绪标记 */
    public static final String TOOLTIP_NOT_READY = "§c✗";

    /** 单层文本：§7 + 层名 + ": " + 标记 */
    public static final String TOOLTIP_LAYER_TEMPLATE = "§7%s: %s";

    /** 层间分隔：空格 + §7| + 空格 + §7 */
    public static final String TOOLTIP_SEPARATOR = " §7| §7";

    // ===================== 日志出口（D1α：common 零日志依赖） =====================

    /**
     * 日志出口。为 {@code null} 时回退到 {@code System.err}（带主 mod 前缀）。
     * 各层 init 时注入自己的 sink（带本层前缀，规范 32）。
     */
    public static volatile Consumer<String> logSink;

    /**
     * 注入日志出口。上层模块用本层的 slf4j logger 实现（例如
     * {@code message -> LOGGER.info("[FJ] " + message)}）。
     */
    public static void setLogSink(Consumer<String> sink) {
        logSink = sink;
    }

    /**
     * 输出一行诊断。{@code message} 是<b>不含前缀</b>的 body，前缀由 sink 负责。
     * sink 抛异常时回退 {@code System.err}，绝不向上抛（失败隔离）。
     */
    public static void log(String message) {
        if (message == null) {
            return;
        }
        Consumer<String> sink = logSink;
        if (sink != null) {
            try {
                sink.accept(message);
                return;
            } catch (Throwable ignored) {
                // 失败隔离：sink 不可用时落到 stderr
            }
        }
        System.err.println(LOG_PREFIX_MAIN + " " + message);
    }
}
