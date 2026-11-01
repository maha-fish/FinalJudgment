package com.maha_fish.finaljudgment.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时 retransform（4.2 功能 3）：每 5 秒对 agentArgs 指定的目标类重转换一次。
 *
 * <p>调用前后用 {@link SelfMarkBridge} 包住（规范 12），保证 Native 层不把我们的重转换当成外部篡改。
 * 目标为空时<b>空转且不打印</b>，避免刷屏；首次真正执行时打一行。</p>
 */
public final class PeriodicRetransformer {

    private static final int INTERVAL_SECONDS = 5;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean FIRST_TICK_LOGGED = new AtomicBoolean();

    private static volatile ScheduledExecutorService scheduler;

    private PeriodicRetransformer() {
    }

    public static synchronized void start(Instrumentation inst) {
        if (inst == null || !STARTED.compareAndSet(false, true)) {
            return;
        }
        if (!inst.isRetransformClassesSupported()) {
            AgentLog.warn("定时 retransform 不可用（不支持 retransformClasses），已停表");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FJ-Agent-Retransformer");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> tick(inst), INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        AgentLog.info("PeriodicRetransformer 已启动: 每 " + INTERVAL_SECONDS + " 秒（目标类数="
                + JudgmentAgent.targets().length + "）");
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static void tick(Instrumentation inst) {
        try {
            // D19：借 tick 兜底重试 ForcedClassRewrite（premain 早期目标类还没加载）
            ForcedClassRewrite.ensure(inst);

            Class<?>[] targets = resolveTargets();
            if (targets.length == 0) {
                return;
            }
            if (FIRST_TICK_LOGGED.compareAndSet(false, true)) {
                AgentLog.info("定时 retransform 首次执行: " + targets.length + " 个类");
            }
            SelfMarkBridge.enter();
            try {
                inst.retransformClasses(targets);
            } catch (Throwable t) {
                AgentLog.warn("定时 retransform 失败: " + t);
            } finally {
                SelfMarkBridge.exit();
            }
        } catch (Throwable t) {
            AgentLog.warn("定时任务异常: " + t);
        }
    }

    private static Class<?>[] resolveTargets() {
        String[] names = JudgmentAgent.targets();
        if (names.length == 0) {
            return new Class<?>[0];
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context == null) {
            context = ClassLoader.getSystemClassLoader();
        }
        Class<?>[] resolved = new Class<?>[names.length];
        int count = 0;
        for (String name : names) {
            try {
                // 注意：不能写成 resolved[count++] = ...，异常时 count 也会自增，
                // 结果数组会带 null，传给 retransformClasses 会触发 JVMTI 断言 + NPE
                resolved[count] = Class.forName(name.replace('/', '.'), false, context);
                count++;
            } catch (Throwable ignored) {
                // 目标还没加载，下次 tick 再试
            }
        }
        if (count == names.length) {
            return resolved;
        }
        Class<?>[] trimmed = new Class<?>[count];
        System.arraycopy(resolved, 0, trimmed, 0, count);
        return trimmed;
    }
}
