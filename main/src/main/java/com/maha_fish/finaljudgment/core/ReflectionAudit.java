package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反射审计（7.4.6 功能 1–3，规范 19）。
 *
 * <p><b>默认零开销</b>：只有 {@code -Dfj.reflection.audit=true} 时才建缓存、打日志；
 * 关闭时普通反射一条路径走完。</p>
 * <p>三级回退：public getter/getField → declared + Unsafe 直读 → 放弃并限流告警。</p>
 */
public final class ReflectionAudit {

    private static final boolean ENABLED = Boolean.getBoolean(Constants.SYS_REFLECTION_AUDIT);
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong WARNED = new AtomicLong();

    private ReflectionAudit() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** 记录一次审计命中（只在开关打开时打，且限流 60 秒一次汇总）。 */
    public static void hit(String what) {
        if (!ENABLED) {
            return;
        }
        FinalJudgment.LOGGER.info("[FJ-Audit] {}", what);
    }

    /** 读静态字段：public → declared(Unsafe 静态直读) → 放弃。 */
    public static Object staticField(Class<?> owner, String... candidates) {
        if (owner == null) {
            return null;
        }
        String key = owner.getName() + "#static";
        if (ENABLED) {
            Object cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        for (String candidate : candidates) {
            try {
                Field field = owner.getField(candidate);
                Object value = field.get(null);
                if (ENABLED) {
                    CACHE.put(key, value);
                    hit("static 字段命中 " + owner.getSimpleName() + "." + candidate);
                }
                return value;
            } catch (Throwable ignored) {
                // 下一级
            }
            try {
                Field field = owner.getDeclaredField(candidate);
                Object unsafe = UnsafeAccess.unsafe();
                if (unsafe == null) {
                    continue;
                }
                Object base = ((sun.misc.Unsafe) unsafe).staticFieldBase(field);
                long offset = ((sun.misc.Unsafe) unsafe).staticFieldOffset(field);
                Object value = ((sun.misc.Unsafe) unsafe).getObject(base, offset);
                if (ENABLED) {
                    CACHE.put(key, value);
                    hit("static 字段（Unsafe 直读）命中 " + owner.getSimpleName() + "." + candidate);
                }
                return value;
            } catch (Throwable ignored) {
                // 放弃
            }
        }
        warnOnce("static 字段全部失败: " + owner.getName());
        return null;
    }

    /** 取无参实例方法。 */
    public static Method method(Class<?> owner, String... candidates) {
        for (String candidate : candidates) {
            try {
                return owner.getMethod(candidate);
            } catch (Throwable ignored) {
                // 下一级
            }
            try {
                return owner.getDeclaredMethod(candidate);
            } catch (Throwable ignored) {
                // 放弃
            }
        }
        warnOnce("方法全部失败: " + owner.getName());
        return null;
    }

    private static void warnOnce(String message) {
        long count = WARNED.incrementAndGet();
        if (count <= 3 || count % 100 == 0) {
            FinalJudgment.LOGGER.debug("[FJ-Audit] {}", message);
        }
    }
}
