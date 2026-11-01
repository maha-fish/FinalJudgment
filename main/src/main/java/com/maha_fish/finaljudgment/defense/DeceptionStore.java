package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeHealthStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deception 层状态存储（扩展 7-9）。
 *
 * <p>真值优先存 Native（{@link NativeHealthStore}，Java 堆外），Java 侧只留诱饵值。
 * 外部读 {@code getHealth()} 得到诱饵；我方通过 {@link #realHealth} 读真值。</p>
 */
public final class DeceptionStore {

    /** 已激活欺骗的实体 → 诱饵血量。 */
    private static final Map<UUID, Float> DECOY = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> NATIVE = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVATED = new AtomicInteger();

    private DeceptionStore() {
    }

    /** 激活欺骗：记录真值（Native 优先）并设置诱饵。 */
    public static void activate(net.minecraft.world.entity.LivingEntity entity, float decoyHealth) {
        if (entity == null) {
            return;
        }
        UUID id = entity.getUUID();
        float real = entity.getHealth();
        boolean nativeBacked = false;
        try {
            if (com.maha_fish.finaljudgment.core.ShadowHealth.isNativeBacked()) {
                NativeHealthStore.nativeHealthSet(id.toString(), real);
                nativeBacked = true;
            }
        } catch (Throwable ignored) {
            // 回退 Java
        }
        if (!nativeBacked) {
            com.maha_fish.finaljudgment.core.ShadowHealth.set(id, real);
        }
        NATIVE.put(id, nativeBacked);
        DECOY.put(id, decoyHealth);
        ACTIVATED.incrementAndGet();
        FinalJudgment.LOGGER.info("[FJ] Deception 已激活: {} 真值={} 诱饵={} 后端={}",
                entity.getType(), real, decoyHealth, nativeBacked ? "Native" : "Java");
    }

    /** 更新真值（我方每次读/写后同步）。 */
    public static void refreshReal(net.minecraft.world.entity.LivingEntity entity) {
        if (entity == null) {
            return;
        }
        UUID id = entity.getUUID();
        if (!DECOY.containsKey(id)) {
            return;
        }
        try {
            float real = entity.getHealth();
            if (Boolean.TRUE.equals(NATIVE.get(id))) {
                NativeHealthStore.nativeHealthSet(id.toString(), real);
            } else {
                com.maha_fish.finaljudgment.core.ShadowHealth.set(id, real);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static void deactivate(net.minecraft.world.entity.LivingEntity entity) {
        if (entity == null) {
            return;
        }
        DECOY.remove(entity.getUUID());
        NATIVE.remove(entity.getUUID());
    }

    public static boolean isActive(net.minecraft.world.entity.Entity entity) {
        return entity != null && DECOY.containsKey(entity.getUUID());
    }

    /** 外部可见值（诱饵）。 */
    public static float decoyOf(net.minecraft.world.entity.LivingEntity entity) {
        if (entity == null) {
            return 0.0F;
        }
        Float decoy = DECOY.get(entity.getUUID());
        return decoy == null ? entity.getHealth() : decoy;
    }

    /** 我方可见值（真值，Native 优先）。 */
    public static float realOf(net.minecraft.world.entity.LivingEntity entity) {
        if (entity == null) {
            return 0.0F;
        }
        UUID id = entity.getUUID();
        try {
            if (Boolean.TRUE.equals(NATIVE.get(id))) {
                return NativeHealthStore.nativeHealthGet(id.toString());
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return com.maha_fish.finaljudgment.core.ShadowHealth.get(id, entity.getHealth());
    }

    public static int activated() {
        return ACTIVATED.get();
    }
}