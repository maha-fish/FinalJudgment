package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * tick 总入口（7.4.5 功能 1，10.0.1 优先级约束）。
 *
 * <p><b>必须是 `LOWEST` + `receiveCanceled = true` + 只处理 `END` 相位</b>：
 * 它要能看到被其他 mod 取消的 tick，并且是最后跑的"总闸"。</p>
 *
 * <p>整段 try-catch：这里抛异常会连累每 tick 的清理逻辑，只在首次打一条 WARN。</p>
 */
public final class LoopHijacker {

    private static final AtomicInteger TICKS = new AtomicInteger();
    private static volatile boolean failedOnce;

    /** Forge 事件总线需要实例（@SubscribeEvent 不能用静态私有构造的类）。 */
    public LoopHijacker() {
    }

    public static void init() {
        FinalJudgment.LOGGER.info("[FJ] LoopHijacker 已就绪（LOWEST + receiveCanceled + END）");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            TICKS.incrementAndGet();
            IsolationManager.tick();
            EntityListGuard.tick();
            com.maha_fish.finaljudgment.attack.KillPressure.tick();
            com.maha_fish.finaljudgment.attack.ServerAutoAttack.tick();
            com.maha_fish.finaljudgment.attack.TrackingAttack.tick();
            com.maha_fish.finaljudgment.attack.DefenseTicker.tick();
            com.maha_fish.finaljudgment.defense.AntiDisarm.tick();
            // EventBusAnchor：每 60 秒金丝雀检查（内部限流）
            com.maha_fish.finaljudgment.defense.EventBusAnchor.tick();
        } catch (Throwable t) {
            if (!failedOnce) {
                failedOnce = true;
                FinalJudgment.LOGGER.warn("[FJ] tick 总入口异常（仅提示一次）: {}", t.toString());
            }
        }
    }

    public static int ticks() {
        return TICKS.get();
    }
}
