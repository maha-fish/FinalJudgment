package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NativeGuard（7.3 功能 3、规范 29）。
 *
 * <p><b>安装时序</b>：必须在 Agent retransform 与 {@code ForcedClassRewrite} 之后采集基线，
 * 否则会把 Agent 的正常修改当成篡改（8.2 step 13）。</p>
 *
 * <p>{@link #verifyAndRepair(String, byte[])} 是 coremod 的
 * {@code nativeInitGuard} 回调目标，签名冻结为 {@code (Ljava/lang/String;[B)[B}：
 * 返回 null 表示"非篡改（JVM 规范化差异）"，返回基线字节表示"真篡改，还原"。</p>
 */
public final class NativeGuard {

    private static final Map<String, byte[]> BASELINES = new ConcurrentHashMap<>();
    private static final AtomicInteger REPAIRS = new AtomicInteger();
    private static volatile boolean installed;

    private NativeGuard() {
    }

    /** 安装：采集基线并报告状态（由 InitPipeline step 13 调用）。 */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        FinalJudgment.LOGGER.info("[FJ] NativeGuard.install: 基线 {} 条（nativeReady={}）",
                BASELINES.size(), NativeBridge.isLoaded());
        try {
            NativeBridge.nativeInitGuard(NativeGuard.class);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] nativeInitGuard 注册失败（Native 未就绪）: {}", t.toString());
        }
    }

    /** 采集一个类的基线（供 SelfClassProtector / 启动扫描调用）。 */
    public static void watch(String internalName, byte[] baseline) {
        if (internalName == null || baseline == null || baseline.length == 0) {
            return;
        }
        BASELINES.put(internalName, baseline);
    }

    /**
     * coremod 回调：返回 null = 放行；返回字节 = 用基线替换。
     * 绝不抛异常（异常会被 C 侧降级为"返回基线"）。
     */
    public static byte[] verifyAndRepair(String internalName, byte[] currentBytes) {
        try {
            byte[] baseline = BASELINES.get(internalName);
            if (baseline == null) {
                return null;                 // 未纳管，放行
            }
            if (currentBytes != null && java.util.Arrays.equals(baseline, currentBytes)) {
                return null;                 // 一致，放行
            }
            REPAIRS.incrementAndGet();
            FinalJudgment.LOGGER.debug("[FJ] NativeGuard 还原: {}", internalName);
            return baseline;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] NativeGuard 校验异常: {}", t.toString());
            return null;
        }
    }

    public static int baselines() {
        return BASELINES.size();
    }

    public static int repairs() {
        return REPAIRS.get();
    }
}
