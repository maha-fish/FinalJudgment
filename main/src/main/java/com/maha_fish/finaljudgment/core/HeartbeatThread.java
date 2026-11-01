package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 心跳线程（7.4.5 功能 2，规范 32 的"无刷屏"约束）。
 *
 * <p><b>硬约束</b>：50ms 周期，只做原子写，<b>不打任何日志</b>（异常也只在首次打一行）。
 * 任何 LOGGER 调用在 20 次/秒的频率下都会把日志写满。</p>
 */
public final class HeartbeatThread {

    /** 50ms（与 Constants.HEARTBEAT_INTERVAL_MS 一致；这里避免为纯工具类引入 common 依赖而写字面量）。 */
    private static final long INTERVAL_MS = 50L;

    private static final AtomicLong BEATS = new AtomicLong();
    private static final AtomicBoolean FAILED_ONCE = new AtomicBoolean();
    private static volatile Thread thread;
    private static volatile boolean running;

    private HeartbeatThread() {
    }

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(HeartbeatThread::loop, "FJ-Heartbeat");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void loop() {
        while (running) {
            try {
                BEATS.incrementAndGet();
                com.maha_fish.finaljudgment.defense.HolderHeartbeat.beat();   // 持有者核心状态校验（50ms）
                // 唯一的动作：原子写
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                if (FAILED_ONCE.compareAndSet(false, true)) {
                    FinalJudgment.LOGGER.warn("[FJ] 心跳线程异常（仅提示一次）: {}", t.toString());
                }
                return;
            }
        }
    }

    public static long beats() {
        return BEATS.get();
    }
}
