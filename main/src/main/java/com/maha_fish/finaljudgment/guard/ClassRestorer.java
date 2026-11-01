package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类还原器（7.3 功能 5）：检测到目标类被改后，用**基线字节**把它还原回去。
 *
 * <p>基线来源（规范 10 的"按类来源"）：自身类用 jar 原始字节；MC 类优先用首次捕获的
 * {@code nativeCaptureRuntimeBytes}，取不到则跳过并记 DEBUG（不报错）。</p>
 */
public final class ClassRestorer {

    private static final Map<String, byte[]> BASELINES = new ConcurrentHashMap<>();
    private static final AtomicInteger RESTORES = new AtomicInteger();

    private ClassRestorer() {
    }

    /** 记录基线（可重复调用，后者覆盖）。 */
    public static void capture(String internalName) {
        if (internalName == null || BASELINES.containsKey(internalName)) {
            return;
        }
        try {
            byte[] bytes = NativeBridge.nativeCaptureRuntimeBytes(internalName);
            if (bytes != null && bytes.length > 0) {
                BASELINES.put(internalName, bytes);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 基线捕获失败 {}: {}", internalName, t.toString());
        }
    }

    /**
     * 用基线还原一个类。
     *
     * @return true 表示已提交还原
     */
    public static boolean restore(String internalName) {
        byte[] baseline = BASELINES.get(internalName);
        if (baseline == null) {
            return false;
        }
        // 走 RedefineExecutor（统一做 net.minecraft 过滤 + 计数），失败再退回 Native 直调
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'),
                    false, Thread.currentThread().getContextClassLoader());
            if (com.maha_fish.finaljudgment.nativebridge.RedefineExecutor.redefine(type, baseline)) {
                RESTORES.incrementAndGet();
                return true;
            }
        } catch (Throwable ignored) {
            // 落到下面的直调
        }
        try {
            boolean ok = NativeBridge.nativeRedefineClass(internalName, baseline);
            if (ok) {
                RESTORES.incrementAndGet();
            }
            return ok;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 还原失败 {}: {}", internalName, t.toString());
            return false;
        }
    }

    public static int baselines() {
        return BASELINES.size();
    }

    public static int restores() {
        return RESTORES.get();
    }
}
