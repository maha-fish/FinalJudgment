package com.maha_fish.finaljudgment;

import java.lang.reflect.Field;

/**
 * 主 mod → coremod 的 KillFlag 反射桥（ENV_SPEC：main 编译期不依赖 coremod）。
 *
 * <p>KillFlag 住在 coremod（MODLAUNCHER，game 层），主 mod 在 mod 层可见它，
 * 但为避免编译期依赖，这里用反射写入。Unit 10 的 DefenseHandler 通过本类设置持有者。</p>
 */
public final class KillFlagBridge {

    private static final String CLS = "com.maha_fish.finaljudgment.coremod.KillFlag";

    private KillFlagBridge() {
    }

    public static void setProtectedPlayer(Object player) {
        write("protectedPlayer", player);
    }

    public static void setTargetEntity(Object target) {
        write("targetEntity", target);
    }

    public static Object getProtectedPlayer() {
        return read("protectedPlayer");
    }

    public static Object getTargetEntity() {
        return read("targetEntity");
    }

    private static void write(String name, Object value) {
        try {
            Field field = type().getField(name);
            field.set(null, value);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] KillFlag 写入失败 {}: {}", name, t.toString());
        }
    }

    private static Object read(String name) {
        try {
            return type().getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> type() throws ClassNotFoundException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return Class.forName(CLS, true, loader != null ? loader : ClassLoader.getSystemClassLoader());
    }
}
