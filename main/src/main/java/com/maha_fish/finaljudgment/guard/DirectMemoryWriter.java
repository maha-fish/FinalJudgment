package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.UnsafeAccess;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直接内存写入（对应示例项目的 {@code DirectMemoryWriter}）。
 *
 * <p>比 {@code FieldWriter} 更底层：{@code FieldWriter} 只能写"能反射到的字段偏移"，
 * 这里用 {@code sun.misc.Unsafe} 的 {@code putLong/putInt/putDouble/putObject} 直接按偏移写，
 * 可绕过 final 字段限制与部分反射屏障。</p>
 *
 * <p>所有写入都带 try/catch，失败只记 debug 日志，不抛异常。</p>
 */
public final class DirectMemoryWriter {

    private static final Object UNSAFE;
    private static final AtomicInteger WRITES = new AtomicInteger();
    private static final AtomicInteger FAILURES = new AtomicInteger();

    static {
        Object unsafe = null;
        try {
            unsafe = UnsafeAccess.unsafe();
        } catch (Throwable ignored) {
            // 保持 null
        }
        UNSAFE = unsafe;
    }

    private DirectMemoryWriter() {
    }

    public static boolean available() {
        return UNSAFE != null;
    }

    public static boolean writeLong(Object base, long offset, long value) {
        return invoke("putLong", new Class<?>[]{Object.class, long.class, long.class}, base, offset, value);
    }

    public static boolean writeInt(Object base, long offset, int value) {
        return invoke("putInt", new Class<?>[]{Object.class, long.class, int.class}, base, offset, value);
    }

    public static boolean writeDouble(Object base, long offset, double value) {
        return invoke("putDouble", new Class<?>[]{Object.class, long.class, double.class}, base, offset, value);
    }

    public static boolean writeFloat(Object base, long offset, float value) {
        return invoke("putFloat", new Class<?>[]{Object.class, long.class, float.class}, base, offset, value);
    }

    public static boolean writeObject(Object base, long offset, Object value) {
        return invoke("putObject", new Class<?>[]{Object.class, long.class, Object.class}, base, offset, value);
    }

    public static long readLong(Object base, long offset, long fallback) {
        Object result = invoke0("getLong", new Class<?>[]{Object.class, long.class}, base, offset);
        return result instanceof Long value ? value : fallback;
    }

    public static int readInt(Object base, long offset, int fallback) {
        Object result = invoke0("getInt", new Class<?>[]{Object.class, long.class}, base, offset);
        return result instanceof Integer value ? value : fallback;
    }

    public static int writes() {
        return WRITES.get();
    }

    public static int failures() {
        return FAILURES.get();
    }

    private static boolean invoke(String name, Class<?>[] types, Object... args) {
        return invoke0(name, types, args) != null;
    }

    private static Object invoke0(String name, Class<?>[] types, Object... args) {
        if (UNSAFE == null) {
            FAILURES.incrementAndGet();
            return null;
        }
        try {
            Method method = UNSAFE.getClass().getMethod(name, types);
            method.setAccessible(true);
            Object result = method.invoke(UNSAFE, args);
            WRITES.incrementAndGet();
            return result == null ? Boolean.TRUE : result;
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            FinalJudgment.LOGGER.debug("[FJ] DirectMemoryWriter.{} 失败: {}", name, t.toString());
            return null;
        }
    }
}