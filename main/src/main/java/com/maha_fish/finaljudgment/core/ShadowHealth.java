package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;
import com.maha_fish.finaljudgment.nativebridge.NativeHealthStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 影子血量（7.4.2 功能 1，规范 7 + 42 的降级链）。
 *
 * <p>Native 优先（值只存在 C 层，Java 堆里没有）→ 不可用时回退 Java Map。</p>
 * <p><b>三基线</b>：工作值 health / 上限值 max / 永久基线 baseline —— 修复时从 baseline 恢复，
 * 不是从 max 恢复（否则对方只要改一次上限就永久污染）。</p>
 * <p>每 tick 校验入口是 {@link #verifyAndRepair(UUID)}，实际调用点在 Unit 7c（避免重复 tick）。</p>
 */
public final class ShadowHealth {

    private static final Map<UUID, float[]> JAVA = new ConcurrentHashMap<>();

    private ShadowHealth() {
    }

    public static boolean isNativeBacked() {
        return NativeBridge.isLoaded();
    }

    public static void set(UUID id, float health) {
        if (isNativeBacked()) {
            NativeHealthStore.nativeHealthSet(id.toString(), health);
            return;
        }
        JAVA.compute(id, (k, v) -> {
            float[] slot = (v == null) ? new float[]{health, health, health} : v;
            slot[0] = health;
            return slot;
        });
    }

    public static float get(UUID id, float fallback) {
        if (isNativeBacked()) {
            return NativeHealthStore.nativeHealthHas(id.toString())
                    ? NativeHealthStore.nativeHealthGet(id.toString()) : fallback;
        }
        float[] slot = JAVA.get(id);
        return slot == null ? fallback : slot[0];
    }

    public static void setMax(UUID id, float max) {
        if (isNativeBacked()) {
            NativeHealthStore.nativeHealthSetMax(id.toString(), max);
            return;
        }
        JAVA.compute(id, (k, v) -> {
            float[] slot = (v == null) ? new float[]{max, max, max} : v;
            slot[1] = max;
            return slot;
        });
    }

    public static float getMax(UUID id, float fallback) {
        if (isNativeBacked()) {
            return NativeHealthStore.nativeHealthHas(id.toString())
                    ? NativeHealthStore.nativeHealthGetMax(id.toString()) : fallback;
        }
        float[] slot = JAVA.get(id);
        return slot == null ? fallback : slot[1];
    }

    public static float getBaseline(UUID id, float fallback) {
        if (isNativeBacked()) {
            return NativeHealthStore.nativeHealthHas(id.toString())
                    ? NativeHealthStore.nativeHealthGetBaseline(id.toString()) : fallback;
        }
        float[] slot = JAVA.get(id);
        return slot == null ? fallback : slot[2];
    }

    public static boolean has(UUID id) {
        return isNativeBacked() ? NativeHealthStore.nativeHealthHas(id.toString()) : JAVA.containsKey(id);
    }

    public static void remove(UUID id) {
        if (isNativeBacked()) {
            NativeHealthStore.nativeHealthRemove(id.toString());
        }
        JAVA.remove(id);
    }

    public static void clearAll() {
        if (isNativeBacked()) {
            NativeHealthStore.nativeHealthClearAll();
        }
        JAVA.clear();
    }

    /**
     * 每 tick 校验与修复（7.4.2 功能 1）：
     * ① 条目丢失 → 重建 ② 工作值异常（NaN/负/超上限）→ 从永久基线恢复 ③ 上限异常 → 用基线修正。
     *
     * @return 是否发生了修复
     */
    public static boolean verifyAndRepair(UUID id) {
        if (!has(id)) {
            return false;
        }
        float baseline = getBaseline(id, 20.0f);
        float max = getMax(id, baseline);
        float health = get(id, baseline);
        boolean repaired = false;
        if (Float.isNaN(max) || max <= 0.0f) {
            setMax(id, baseline);
            max = baseline;
            repaired = true;
        }
        if (Float.isNaN(health) || health < 0.0f || health > max) {
            set(id, baseline);
            repaired = true;
        }
        if (repaired) {
            FinalJudgment.LOGGER.debug("[FJ] 影子血量已从永久基线修复: {} -> {}", id, baseline);
        }
        return repaired;
    }

    /** 初始化自检：一次写/读/基线/上限往返，结果进日志（不需要游戏内操作）。 */
    public static void selfTest() {
        UUID probe = UUID.nameUUIDFromBytes("finaljudgment-selftest".getBytes());
        set(probe, 20.0f);
        setMax(probe, 40.0f);
        set(probe, 30.0f);
        float health = get(probe, -1.0f);
        float max = getMax(probe, -1.0f);
        float baseline = getBaseline(probe, -1.0f);
        boolean ok = Math.abs(health - 30.0f) < 0.001f
                && Math.abs(max - 40.0f) < 0.001f
                && Math.abs(baseline - 20.0f) < 0.001f;
        remove(probe);
        FinalJudgment.LOGGER.info("[FJ] ShadowHealth: 后端={} 写/读={} 上限={} 永久基线={} 自检={}",
                isNativeBacked() ? "Native" : "Java", health, max, baseline, ok ? "通过" : "失败");
    }
}
