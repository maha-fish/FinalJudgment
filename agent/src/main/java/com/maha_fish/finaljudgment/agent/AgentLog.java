package com.maha_fish.finaljudgment.agent;

import java.util.function.Consumer;

/**
 * Agent 侧日志。
 *
 * <p>规范 32：[FJ-Agent] 前缀。Agent 由 System ClassLoader 加载，启动早期（premain）没有 slf4j，
 * 因此默认走 {@code System.err}（Forge 会一并写入 latest.log）；主 mod 可在附加成功后通过
 * 反射调用 {@link InstHolder#setLogSink(Consumer)} 把日志接进自己的 slf4j。</p>
 */
public final class AgentLog {

    private static final String PREFIX = "[FJ-Agent]";

    private static volatile Consumer<String> sink;

    private AgentLog() {
    }

    /** 注入日志出口。传入的 line 已包含 [FJ-Agent] 前缀。 */
    public static void setSink(Consumer<String> newSink) {
        sink = newSink;
    }

    public static void info(String message) {
        emit(message);
    }

    public static void warn(String message) {
        emit(message);
    }

    public static void debug(String message) {
        emit(message);
    }

    private static void emit(String message) {
        String line = PREFIX + " " + message;
        Consumer<String> currentSink = sink;
        if (currentSink != null) {
            try {
                currentSink.accept(line);
                return;
            } catch (Throwable ignored) {
                // 失败隔离：sink 不可用时落到 stderr
            }
        }
        System.err.println(line);
    }
}
