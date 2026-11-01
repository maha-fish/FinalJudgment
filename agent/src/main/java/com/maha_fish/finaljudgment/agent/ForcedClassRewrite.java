package com.maha_fish.finaljudgment.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ForcedClassRewrite（4.2 功能 4）：Mixin 应用完之后，再抢一层 retransform。
 *
 * <p>用 <b>Instrumentation.retransformClasses</b>（允许 vanilla 类），
 * <b>不是</b> JVMTI RedefineClasses（规范 31）。</p>
 *
 * <p>触发点（D19）：agentmain 结束时先试一次；未成功则由周期的 tick 每次重试，最多 12 次（约 60 秒）。
 * 这样 premain 路径（MC 类还没加载）也能自动补上，且不需要主 mod 反射调 agent。</p>
 */
public final class ForcedClassRewrite {

    private static final String[] TARGETS = {
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity",
    };

    private static final int MAX_ATTEMPTS = 12;

    private static final AtomicBoolean DONE = new AtomicBoolean();
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();
    private static final AtomicBoolean GAVE_UP_LOGGED = new AtomicBoolean();

    private ForcedClassRewrite() {
    }

    public static void ensure(Instrumentation inst) {
        if (inst == null || DONE.get()) {
            return;
        }
        if (ATTEMPTS.get() >= MAX_ATTEMPTS) {
            if (GAVE_UP_LOGGED.compareAndSet(false, true)) {
                AgentLog.warn("ForcedClassRewrite 放弃: 重试 " + MAX_ATTEMPTS + " 次后目标类仍未加载");
            }
            return;
        }
        if (!inst.isRetransformClassesSupported()) {
            if (ATTEMPTS.incrementAndGet() == 1) {
                AgentLog.warn("ForcedClassRewrite 不可用：不支持 retransformClasses");
            }
            return;
        }

        Class<?>[] resolved = resolveTargets();
        if (resolved.length == 0) {
            if (ATTEMPTS.incrementAndGet() == 1) {
                AgentLog.warn("ForcedClassRewrite 暂缓: 目标类未加载（premain 早期属正常，等待周期重试）");
            }
            return;
        }
        if (!DONE.compareAndSet(false, true)) {
            return;
        }

        SelfMarkBridge.enter();
        try {
            inst.retransformClasses(resolved);
            AgentLog.info("ForcedClassRewrite 完成: " + resolved.length
                    + " 个 vanilla 类（Instrumentation.retransformClasses，规范 31）");
        } catch (Throwable t) {
            AgentLog.warn("ForcedClassRewrite 失败: " + t);
        } finally {
            SelfMarkBridge.exit();
        }
    }

    private static Class<?>[] resolveTargets() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context == null) {
            context = ClassLoader.getSystemClassLoader();
        }
        Class<?>[] resolved = new Class<?>[TARGETS.length];
        int count = 0;
        for (String name : TARGETS) {
            try {
                // 同样不能写 resolved[count++] = ...（异常时 count 会自增 → 数组带 null）
                resolved[count] = Class.forName(name, false, context);
                count++;
            } catch (Throwable ignored) {
                // 该类在当前阶段还不可见
            }
        }
        if (count == TARGETS.length) {
            return resolved;
        }
        Class<?>[] trimmed = new Class<?>[count];
        System.arraycopy(resolved, 0, trimmed, 0, count);
        return trimmed;
    }
}
