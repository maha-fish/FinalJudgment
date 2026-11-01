package com.maha_fish.finaljudgment.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Unsafe 访问（7.4.1 功能 1，规范 30）。
 *
 * <p>主路径：{@code MethodHandles.privateLookupIn(sun.misc.Unsafe.class, lookup)} 读 {@code theUnsafe}。
 * jdk.unsupported 对 {@code sun.misc} 是 open 的，所以**不需要 --add-opens、也不需要 setAccessible**。</p>
 *
 * <p><b>关于"备用 IMPL_LOOKUP"（原文要求）</b>：读 {@code MethodHandles.Lookup.IMPL_LOOKUP} 本身就是
 * 访问 java.base 私有成员，唯一可行手段是 {@code setAccessible(true)} —— 这与规范 30 直接冲突。
 * 因此这里**不实现该备用路径**，改为失败即告警并置不可用（降级由调用方处理）。此冲突已记入设计报告。</p>
 */
public final class UnsafeAccess {

    private static volatile Object unsafeInstance;
    private static volatile boolean initialised;
    private static volatile String path = "none";

    private UnsafeAccess() {
    }

    public static boolean available() {
        init();
        return unsafeInstance != null;
    }

    public static Object unsafe() {
        init();
        return unsafeInstance;
    }

    public static String path() {
        init();
        return path;
    }

    private static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            // 静态字段取值：findStaticGetter 返回零参 MethodHandle（unreflectVarHandle 的 get 参数列表不同，
            // 用错会抛 WrongMethodTypeException —— 已实测踩坑）
            MethodHandle getter = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findStaticGetter(type, "theUnsafe", type);
            unsafeInstance = getter.invoke();
            path = "privateLookupIn(sun.misc.Unsafe).findStaticGetter";
        } catch (Throwable t) {
            unsafeInstance = null;
            path = "unavailable: " + t;
            com.maha_fish.finaljudgment.FinalJudgment.LOGGER.warn(
                    "[FJ] Unsafe 不可用（字段直写将降级）: {}", t.toString());
        }
    }
}
