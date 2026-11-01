package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.AgentBridge;
import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分层降级总表 + 降级验证（扩展 15 / 扩展 16 的实现）。
 *
 * <p>把规范里"能力 → 首选 / 回退 1 / 回退 2 / 回退 3"的表格变成<b>运行时实测</b>：
 * 对每项能力逐级探测，报告当前实际生效的层级，并在 {@code -Dfj.verify.degrade=true}
 * 时额外做一致性校验（例如 Native 不可用时，Agent/Coremod 路径必须仍可用）。</p>
 */
public final class FallbackMatrix {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private FallbackMatrix() {
    }

    /** 输出降级总表的实测结果（幂等，只打印一次）。 */
    public static void report() {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        boolean nativeReady = safeNative();
        boolean agentReady = safeAgent();
        boolean coremodReady = Boolean.getBoolean("fj.coremod.loaded");
        boolean unsafeReady = com.maha_fish.finaljudgment.core.UnsafeAccess.available();

        StringBuilder sb = new StringBuilder("\n[FJ] ===== 降级总表（实测）=====");

        // 直杀实体：NativeKill → UnsafeWrite → VanillaSetRemoved
        sb.append("\n[FJ] 直杀实体      : ")
                .append(nativeReady ? "NativeKill"
                        : unsafeReady ? "UnsafeWrite"
                        : "VanillaSetRemoved");

        // 改方法体：Native Redefine → Agent ASM → Coremod 守卫
        sb.append("\n[FJ] 改方法体      : ")
                .append(nativeReady && RedefineExecutorAvailable() ? "Native Redefine"
                        : agentReady ? "Agent ASM"
                        : coremodReady ? "Coremod 守卫"
                        : "Mixin 注入");

        // 集合摘除：StorageExtract → MapSweep
        sb.append("\n[FJ] 集合摘除      : ")
                .append(isLoaded("com.maha_fish.finaljudgment.core.StorageExtract") ? "StorageExtract" : "MapSweep");

        // 血量存储：Native HealthEntry → Java 影子
        sb.append("\n[FJ] 血量存储      : ")
                .append(nativeReady ? "Native HealthEntry" : "Java 影子血量");

        // 类保护：NativeGuard → SelfGuard(类+方法级) → ClassRestorer
        sb.append("\n[FJ] 类保护        : ")
                .append(com.maha_fish.finaljudgment.guard.NativeGuard.class != null && nativeReady ? "NativeGuard" : "")
                .append(SelfGuard.baselines() > 0 ? "SelfGuard(" + SelfGuard.baselines() + " 类)" : "")
                .append(" + ClassRestorer(" + ClassRestorer.baselines() + " 条)");

        // 我方标记
        sb.append("\n[FJ] 我方标记      : ")
                .append(property("fj.agent.patch.registered"))
                .append(" / ").append(property("fj.coremod.loaded"));

        // 类枚举 / 字节码捕获
        sb.append("\n[FJ] 类枚举        : ")
                .append(nativeReady ? "JVMTI GetLoadedClasses" : "不可用（跳过类级扫描）");
        sb.append("\n[FJ] 字节码捕获    : ")
                .append(nativeReady ? "JVMTI Capture" : "ClassLoader 资源流");

        // 层就绪
        sb.append("\n[FJ] 层就绪        : Native=").append(nativeReady)
                .append(" Agent=").append(agentReady)
                .append(" Coremod=").append(coremodReady)
                .append(" Bootstrap=").append(Boolean.getBoolean("fj.bootstrap.loaded"));

        sb.append("\n[FJ] ==========================");
        FinalJudgment.LOGGER.info(sb.toString());

        // 扩展 16：降级一致性验证
        if (Boolean.parseBoolean(System.getProperty("fj.verify.degrade", "false"))) {
            verify(nativeReady, agentReady, coremodReady);
        }
    }

    /** 降级验证：任何一层缺失时，其余层必须仍能承担关键能力。 */
    private static void verify(boolean nativeReady, boolean agentReady, boolean coremodReady) {
        StringBuilder sb = new StringBuilder("\n[FJ] ===== 降级验证 =====");
        int failures = 0;

        if (!nativeReady) {
            boolean canKill = com.maha_fish.finaljudgment.core.UnsafeAccess.available();
            sb.append("\n[FJ] 关 Native → 直杀实体: ").append(canKill ? "OK(Unsafe)" : "FAIL");
            if (!canKill) {
                failures++;
            }
            boolean canRewrite = agentReady || coremodReady;
            sb.append("\n[FJ] 关 Native → 改方法体: ").append(canRewrite ? "OK" : "FAIL");
            if (!canRewrite) {
                failures++;
            }
        }
        if (!agentReady) {
            boolean canRewrite = coremodReady;
            sb.append("\n[FJ] 关 Agent → 改方法体: ").append(canRewrite ? "OK(Coremod)" : "FAIL");
            if (!canRewrite) {
                failures++;
            }
        }
        sb.append("\n[FJ] 失败项: ").append(failures);
        sb.append("\n[FJ] =====================");
        FinalJudgment.LOGGER.info(sb.toString());
    }

    private static boolean safeNative() {
        try {
            return NativeBridge.isLoaded();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeAgent() {
        try {
            return AgentBridge.isReady();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean RedefineExecutorAvailable() {
        return isLoaded("com.maha_fish.finaljudgment.nativebridge.RedefineExecutor");
    }

    private static boolean isLoaded(String className) {
        try {
            Class.forName(className, false, FallbackMatrix.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        return name.replace("fj.", "") + "=" + (value == null ? "未设置" : value);
    }
}
