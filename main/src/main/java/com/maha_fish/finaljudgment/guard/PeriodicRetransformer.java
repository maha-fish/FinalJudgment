package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 周期性重应用（对应示例项目的 {@code PeriodicRetransformer}）。
 *
 * <p>{@link SelfGuard} 保护"我们自己的类"不被改写；
 * 本类保护"我们对别人的改写"不被顶掉——两者互为镜像。</p>
 *
 * <p>每 60 秒做两件事：</p>
 * <ol>
 *   <li>调用 {@link MethodRedirect#reapplyAll()}：把已登记的"钉死返回值"重新写一遍
 *       （对手 mod 若把自己的类改回去，这里会再次压回）</li>
 *   <li>调用 {@link SelfGuard#verifyAll()}：校验我们自己的关键类是否被动过</li>
 * </ol>
 *
 * <p>仅在 {@code -Dfj.methodRedirect=true} 时执行改写部分；校验部分始终生效。</p>
 */
public final class PeriodicRetransformer {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicLong ROUNDS = new AtomicLong();
    private static final AtomicLong REAPPLIED = new AtomicLong();

    private PeriodicRetransformer() {
    }

    /** 由防御 tick 首次调用时启动守护线程。 */
    public static void touch() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread thread = new Thread(PeriodicRetransformer::loop, "FJ-PeriodicRetransformer");
            thread.setDaemon(true);
            thread.start();
            FinalJudgment.LOGGER.info("[FJ] 周期性重应用已启用（60s/轮，methodRedirect={}）",
                    MethodRedirect.enabled());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] 周期性重应用启动失败: {}", t.toString());
        }
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(60_000L);
                runOnce();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] 周期性重应用异常: {}", t.toString());
            }
        }
    }

    /** 执行一轮（也可手动调用）。 */
    public static void runOnce() {
        ROUNDS.incrementAndGet();
        try {
            int reapplied = MethodRedirect.reapplyAll();
            if (reapplied > 0) {
                REAPPLIED.addAndGet(reapplied);
                FinalJudgment.LOGGER.debug("[FJ] 周期性重应用: 本轮重新改写 {} 个方法", reapplied);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] reapplyAll 异常: {}", t.toString());
        }
        try {
            SelfGuard.verifyAll();
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] verifyAll 异常: {}", t.toString());
        }
    }

    public static long rounds() {
        return ROUNDS.get();
    }

    public static long reapplied() {
        return REAPPLIED.get();
    }
}
