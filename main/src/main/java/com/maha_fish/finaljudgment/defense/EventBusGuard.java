package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件总线守护（参考项目 {@code EventBusGuard} 的功能对应物）。
 *
 * <p>只保护**确实带 {@code @SubscribeEvent} 方法**的类（避免把普通类也塞进列表）。
 * 告警限流 60 秒（规范 32：不得刷屏）。</p>
 *
 * <p>与 {@link EventBusAnchor} 分工：Anchor 负责"金丝雀探测 + 重新注册"，
 * Guard 负责"收集需要保护的类 + 限流告警"。</p>
 */
public final class EventBusGuard {

    private static final AtomicLong LAST_WARN_MS = new AtomicLong();
    private static final long WARN_INTERVAL_MS = 60_000L;

    private EventBusGuard() {
    }

    public static void init() {
        FinalJudgment.LOGGER.info("[FJ] EventBusGuard 已就绪（只保护含 @SubscribeEvent 的类，告警限流 60s）");
    }

    /** 是否属于"应保护"的类：至少有一个 @SubscribeEvent 方法。 */
    public static boolean worthProtecting(Class<?> type) {
        if (type == null) {
            return false;
        }
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(SubscribeEvent.class)) {
                    return true;
                }
            }
            for (Method method : type.getMethods()) {
                if (method.isAnnotationPresent(SubscribeEvent.class)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 反射失败视为不保护
        }
        return false;
    }

    /** 登记一个监听器：只有值得保护的才进 Anchor 的重新注册列表。 */
    public static void register(Object listener) {
        if (listener == null) {
            return;
        }
        if (!worthProtecting(listener.getClass())) {
            return;
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(listener);
        EventBusAnchor.remember(listener);
    }

    /** 限流告警。 */
    public static void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        long last = LAST_WARN_MS.get();
        if (now - last < WARN_INTERVAL_MS) {
            return;
        }
        LAST_WARN_MS.set(now);
        FinalJudgment.LOGGER.warn("[FJ] EventBusGuard: {}", message);
    }
}
