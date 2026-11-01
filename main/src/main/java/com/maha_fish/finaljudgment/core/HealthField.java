package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 定位 {@code DATA_HEALTH_ID}（7.4.1 功能 3）。
 *
 * <p>⚠️ 1.20.1 里该字段在 <b>Entity</b> 上（不是 LivingEntity），名字 dev 为 {@code DATA_HEALTH_ID}、
 * 生产为 {@code f_20915_} —— 双候选 + 类型匹配。</p>
 */
public final class HealthField {

    private static final String ENTITY = "net.minecraft.world.entity.Entity";
    private static final String[] CANDIDATES = {"DATA_HEALTH_ID", "f_20915_"};

    private static volatile Object accessor;
    private static volatile boolean resolved;

    private HealthField() {
    }

    public static Object accessor() {
        resolve();
        return accessor;
    }

    public static int accessorId() {
        Object value = accessor();
        if (value == null) {
            return -1;
        }
        try {
            Method getId = value.getClass().getMethod("getId");
            return (Integer) getId.invoke(value);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> entity = Class.forName(ENTITY);
            for (String candidate : CANDIDATES) {
                try {
                    Field field = entity.getDeclaredField(candidate);
                    // 只读静态字段：用 get(null)（public/protected 时可用），不 setAccessible
                    field.setAccessible(false);
                    Object value = readStatic(field);
                    if (value != null) {
                        accessor = value;
                        FinalJudgment.LOGGER.info("[FJ] HealthField: 命中 {} (accessor id={})",
                                candidate, accessorId());
                        return;
                    }
                } catch (Throwable ignored) {
                    // 换下一个候选
                }
            }
            FinalJudgment.LOGGER.warn("[FJ] HealthField: 未找到 DATA_HEALTH_ID/f_20915_");
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] HealthField 定位失败: {}", t.toString());
        }
    }

    private static Object readStatic(Field field) {
        try {
            return field.get(null);
        } catch (Throwable t) {
            // 落到 Unsafe 直读静态字段
            try {
                Object unsafe = UnsafeAccess.unsafe();
                if (unsafe == null) {
                    return null;
                }
                Object base = ((sun.misc.Unsafe) unsafe).staticFieldBase(field);
                long offset = ((sun.misc.Unsafe) unsafe).staticFieldOffset(field);
                return ((sun.misc.Unsafe) unsafe).getObject(base, offset);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
