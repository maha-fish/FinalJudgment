package com.maha_fish.finaljudgment.agent;

import java.lang.instrument.Instrumentation;
import java.util.function.Consumer;

/**
 * Instrumentation 持有者 —— 跨 ClassLoader 的唯一桥梁（4.2 功能 1）。
 *
 * <p>Agent 由 System ClassLoader 加载，主 mod 在 ModLauncher 的 TransformingClassLoader 里，
 * 两者不能直接引用类型，只能反射。因此这里的<b>方法名是冻结的公共 API</b>：</p>
 *
 * <pre>
 * Class.forName("com.maha_fish.finaljudgment.agent.InstHolder", true,
 *               ClassLoader.getSystemClassLoader())
 *     .getMethod("isReady")            // → boolean
 *     .getMethod("get")                // → Instrumentation
 *     .getMethod("setLogSink", Consumer.class)   // 可选，注入日志出口
 * </pre>
 */
public final class InstHolder {

    private static volatile Instrumentation instrumentation;

    private InstHolder() {
    }

    /** 由 {@link JudgmentAgent} 在 premain / agentmain 中调用。 */
    public static void set(Instrumentation inst) {
        instrumentation = inst;
    }

    /** 主 mod 反射读取；未就绪时返回 null（调用方必须判空，规范 40）。 */
    public static Instrumentation get() {
        return instrumentation;
    }

    /** 主 mod 反射轮询（每 100ms，最多 5 秒）。 */
    public static boolean isReady() {
        return instrumentation != null;
    }

    /** 可选：主 mod 注入日志出口，签名必须用 java.util.function.Consumer（跨 CL 可见）。 */
    public static void setLogSink(Consumer<String> sink) {
        AgentLog.setSink(sink);
    }
}
