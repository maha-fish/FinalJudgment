package com.maha_fish.finaljudgment.core;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字段直写（7.4.1 功能 2，规范 30）：缓存 offset + Unsafe 直写，<b>不走 setAccessible</b>。
 * 字段定位：名字候选（MCP/SRG 双写）→ 类型名后缀兜底（如 EndsWith("RemovalReason")）。
 */
public final class FieldWriter {

    private static final Map<String, Long> OFFSETS = new ConcurrentHashMap<>();

    private FieldWriter() {
    }

    public static boolean available() {
        return UnsafeAccess.available();
    }

    /** 读 offset；-1 表示找不到。 */
    public static long offsetOf(Class<?> owner, String[] candidates, String typeSuffix) {
        String key = owner.getName() + "#" + String.join("|", candidates) + "#" + typeSuffix;
        Long cached = OFFSETS.get(key);
        if (cached != null) {
            return cached;
        }
        long offset = -1L;
        Field field = findField(owner, candidates, typeSuffix);
        if (field != null && UnsafeAccess.available()) {
            try {
                offset = ((sun.misc.Unsafe) UnsafeAccess.unsafe()).objectFieldOffset(field);
            } catch (Throwable ignored) {
                offset = -1L;
            }
        }
        OFFSETS.put(key, offset);
        return offset;
    }

    private static Field findField(Class<?> owner, String[] candidates, String typeSuffix) {
        for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
            for (String candidate : candidates) {
                try {
                    return current.getDeclaredField(candidate);
                } catch (Throwable ignored) {
                    // 换下一个候选
                }
            }
            if (typeSuffix != null) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType().getSimpleName().endsWith(typeSuffix)) {
                        return field;
                    }
                }
            }
        }
        return null;
    }

    public static boolean writeObject(Object target, long offset, Object value) {
        if (target == null || offset < 0 || !UnsafeAccess.available()) {
            return false;
        }
        try {
            ((sun.misc.Unsafe) UnsafeAccess.unsafe()).putObject(target, offset, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object readObject(Object target, long offset) {
        if (target == null || offset < 0 || !UnsafeAccess.available()) {
            return null;
        }
        try {
            return ((sun.misc.Unsafe) UnsafeAccess.unsafe()).getObject(target, offset);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean writeInt(Object target, long offset, int value) {
        if (target == null || offset < 0 || !UnsafeAccess.available()) {
            return false;
        }
        try {
            ((sun.misc.Unsafe) UnsafeAccess.unsafe()).putInt(target, offset, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写 boolean 字段（必须用 putBoolean；把 Boolean 对象塞进原始字段会写坏内存）。 */
    public static boolean writeBoolean(Object target, long offset, boolean value) {
        if (target == null || offset < 0 || !UnsafeAccess.available()) {
            return false;
        }
        try {
            ((sun.misc.Unsafe) UnsafeAccess.unsafe()).putBoolean(target, offset, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean writeFloat(Object target, long offset, float value) {
        if (target == null || offset < 0 || !UnsafeAccess.available()) {
            return false;
        }
        try {
            ((sun.misc.Unsafe) UnsafeAccess.unsafe()).putFloat(target, offset, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static float readFloat(Object target, long offset, float fallback) {
        if (target == null || offset < 0 || !UnsafeAccess.available()) {
            return fallback;
        }
        try {
            return ((sun.misc.Unsafe) UnsafeAccess.unsafe()).getFloat(target, offset);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 供诊断：把类及其父类的字段名+类型打出来（一次性）。 */
    public static String describe(Class<?> owner) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            sb.append(c.getSimpleName().toLowerCase(Locale.ROOT)).append("=[");
            boolean first = true;
            for (Field f : c.getDeclaredFields()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName());
            }
            sb.append("] ");
        }
        return sb.toString();
    }
}
