package com.maha_fish.finaljudgment.coremod;

import java.lang.reflect.Method;

/**
 * Coremod 侧日志（规范 32：[FJ-Coremod] 前缀）。
 *
 * <p>关键教训：<b>Forge 1.20.1 不会把 System.err 写进 latest.log</b>（只进控制台），
 * 所以必须走 log4j。Coremod 编译期不依赖 MC，这里用反射拿 log4j，拿不到才回退 stderr。</p>
 */
public final class CoremodLog {

    private static final String PREFIX = "[FJ-Coremod]";
    private static final String LOGGER_NAME = "finaljudgment_coremod";

    private static volatile Object logger;
    private static volatile Method infoMethod;
    private static volatile Method warnMethod;
    private static volatile boolean resolved;

    private CoremodLog() {
    }

    public static void info(String message) {
        emit(false, message);
    }

    public static void warn(String message) {
        emit(true, message);
    }

    private static void emit(boolean warning, String message) {
        resolve();
        Method method = warning ? warnMethod : infoMethod;
        Object target = logger;
        if (method != null && target != null) {
            try {
                method.invoke(target, PREFIX + " " + message);
                return;
            } catch (Throwable ignored) {
                // 落到 stderr
            }
        }
        System.err.println(PREFIX + (warning ? " [WARN] " : " ") + message);
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> manager = Class.forName("org.apache.logging.log4j.LogManager", true, loader);
            Class<?> loggerType = Class.forName("org.apache.logging.log4j.Logger", true, loader);
            logger = manager.getMethod("getLogger", String.class).invoke(null, LOGGER_NAME);
            infoMethod = loggerType.getMethod("info", String.class);
            warnMethod = loggerType.getMethod("warn", String.class);
            resolved = true;
        } catch (Throwable t) {
            resolved = true;
            System.err.println(PREFIX + " [WARN] log4j 不可用，回退 stderr: " + t);
        }
    }
}
