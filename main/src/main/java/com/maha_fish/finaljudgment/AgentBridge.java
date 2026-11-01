package com.maha_fish.finaljudgment;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Agent 桥接（7.2 功能 1）：用系统 ClassLoader 反射读 {@code InstHolder}，缓存结果。
 * 探测失败用 DEBUG 级（不是错误）。
 */
public final class AgentBridge {

    private static final String HOLDER = "com.maha_fish.finaljudgment.agent.InstHolder";

    private static volatile boolean resolved;
    private static volatile boolean probeLogged;
    private static Method isReadyMethod;
    private static Method getMethod;
    private static Method setLogSinkMethod;

    private AgentBridge() {
    }

    public static boolean isReady() {
        resolve();
        if (isReadyMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isReadyMethod.invoke(null));
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] AgentBridge.isReady 失败: {}", t.toString());
            return false;
        }
    }

    /** 返回 java.lang.instrument.Instrumentation（用 Object 持有，避免模块解析问题）。 */
    public static Object getInstrumentation() {
        resolve();
        if (getMethod == null) {
            return null;
        }
        try {
            return getMethod.invoke(null);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] AgentBridge.get 失败: {}", t.toString());
            return null;
        }
    }

    /** 可选：把 agent 日志接进主 mod 的 slf4j。 */
    public static boolean installLogSink() {
        resolve();
        if (setLogSinkMethod == null) {
            return false;
        }
        try {
            Consumer<String> sink = line -> FinalJudgment.LOGGER.info("{}", line);
            setLogSinkMethod.invoke(null, sink);
            return true;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] AgentBridge.setLogSink 失败: {}", t.toString());
            return false;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        // 关键：只有【成功】才置 resolved，否则附加成功后永远拿不到 Instrumentation
        try {
            Class<?> holder = Class.forName(HOLDER, true, ClassLoader.getSystemClassLoader());
            isReadyMethod = holder.getMethod("isReady");
            getMethod = holder.getMethod("get");
            setLogSinkMethod = holder.getMethod("setLogSink", Consumer.class);
            resolved = true;
            FinalJudgment.LOGGER.debug("[FJ] AgentBridge 已解析 {}", HOLDER);
        } catch (Throwable t) {
            if (!probeLogged) {
                probeLogged = true;
                FinalJudgment.LOGGER.debug("[FJ] Agent 尚未就绪（{}）: {}", HOLDER, t.toString());
            }
        }
    }
}
