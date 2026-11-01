package com.maha_fish.finaljudgment.coremod;

import java.lang.reflect.Field;

/**
 * Coremod 侧独立守卫（5.2 功能 4）。
 *
 * <p>Coremod 不能引用主模块的 DefenseHandler。这里用<b>反射</b>读 common 的
 * {@code KillFlag}（失败即降级为 false，绝不抛），而不是直接 GETSTATIC ——
 * 因为 CoremodGuard 自身由 game 层加载，直接引用 mod 层类可能在加载期就失败。</p>
 *
 * <p>注入到目标方法里的字节码仍然是直接 {@code GETSTATIC KillFlag.protectedPlayer}
 * （零方法调用，对方 hook 不到），本类只给 tick 检查等 Java 侧路径用。</p>
 */
public final class CoremodGuard {

    private static final String KILL_FLAG = "com.maha_fish.finaljudgment.coremod.KillFlag";
    private static final String FIELD_PROTECTED = "protectedPlayer";
    private static final String FIELD_TARGET = "targetEntity";

    private CoremodGuard() {
    }

    public static boolean isProtected(Object entity) {
        return entity != null && entity == readField(FIELD_PROTECTED);
    }

    public static boolean isTarget(Object entity) {
        return entity != null && entity == readField(FIELD_TARGET);
    }

    private static Object readField(String name) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> holder = Class.forName(KILL_FLAG, false, loader);
            Field field = holder.getField(name);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
