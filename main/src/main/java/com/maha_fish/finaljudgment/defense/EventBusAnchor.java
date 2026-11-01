package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线结构锚点（参考项目 {@code EventBusAnchor} 的功能对应物）。
 *
 * <p>问题：其他模组可能通过反射把我们的 `@SubscribeEvent` 监听器从
 * {@code MinecraftForge.EVENT_BUS} 里摘掉 —— 一旦发生，我们的防御链会**静默失效**
 * （没有异常、没有日志，只是不再收到事件）。</p>
 *
 * <p>做法：<b>金丝雀事件</b>。我们自定义一个事件类并注册一个监听器，
 * 每 60 秒 post 一次；如果没收到 → 判定监听器被摘 → 重新注册全部登记过的监听器并告警。
 * 这比对比内部 listener 列表更稳定（不依赖 Forge 内部字段名）。</p>
 */
public final class EventBusAnchor {

    private static final long CHECK_INTERVAL_MS = 60_000L;
    /** 我们注册过的监听器实例（重新注册时用）。 */
    private static final List<Object> REGISTERED = new CopyOnWriteArrayList<>();
    private static volatile long lastCheckMs;
    private static volatile boolean canarySeen;
    private static volatile boolean canaryArmed;
    private static volatile int recoveries;

    private EventBusAnchor() {
    }

    /** 金丝雀事件（只用于探测监听器是否还在）。 */
    public static final class Canary extends Event {
        private static final Canary INSTANCE = new Canary();
    }

    public static void init() {
        try {
            IEventBus bus = MinecraftForge.EVENT_BUS;
            bus.addListener(EventBusAnchor::onCanary);
            canaryArmed = true;
            FinalJudgment.LOGGER.info("[FJ] EventBusAnchor 已就绪（金丝雀检查 每 60s）");
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] EventBusAnchor 初始化失败: {}", t.toString());
        }
    }

    /** 记录一个由我们注册的监听器，便于被摘后重新注册。 */
    public static void remember(Object listener) {
        if (listener != null && !REGISTERED.contains(listener)) {
            REGISTERED.add(listener);
        }
    }

    private static void onCanary(Canary event) {
        canarySeen = true;
    }

    /** 每 tick 调用（内部限流到 60 秒）。 */
    public static void tick() {
        if (!canaryArmed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckMs = now;
        try {
            // 不能在事件派发过程中嵌套 post（Forge 会抛 "phase already NORMAL"）
            // 因此把金丝雀投递延后到本 tick 结束后的 execute 队列里
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                try {
                    canarySeen = false;
                    MinecraftForge.EVENT_BUS.post(Canary.INSTANCE);
                    if (!canarySeen) {
                        recoveries++;
                        FinalJudgment.LOGGER.warn("[FJ] EventBusAnchor: 金丝雀未收到 —— 监听器疑似被摘，重新注册 {} 个", REGISTERED.size());
                        reregisterAll();
                    }
                } catch (Throwable t) {
                    FinalJudgment.LOGGER.debug("[FJ] EventBusAnchor 延后检查异常: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] EventBusAnchor 检查异常: {}", t.toString());
        }
    }

    /** 被动通知：EventBusGuard 判定需要恢复时调用。 */
    public static void reregisterAll() {
        for (Object listener : REGISTERED) {
            try {
                MinecraftForge.EVENT_BUS.unregister(listener);
            } catch (Throwable ignored) {
                // 未注册过会抛，忽略
            }
            try {
                MinecraftForge.EVENT_BUS.register(listener);
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] 重新注册失败 {}: {}", listener.getClass().getName(), t.toString());
            }
        }
    }

    public static int registered() {
        return REGISTERED.size();
    }

    public static int recoveries() {
        return recoveries;
    }
}
