package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实体列表守护（7.4.5 功能 3）。
 *
 * <p>节奏约束：启动后<b>等 100 tick</b> 才首建，之后<b>每 5 秒（100 tick）整体重建</b>。
 * 绝不每 tick 扫全量（`getAllEntities` 是 O(n)）。</p>
 */
public final class EntityListGuard {

    private static final int FIRST_DELAY_TICKS = 100;
    private static final int REBUILD_INTERVAL_TICKS = 100;

    private static final AtomicInteger TICKS = new AtomicInteger();
    private static final AtomicInteger REBUILDS = new AtomicInteger();
    private static volatile int lastCount;

    private EntityListGuard() {
    }

    public static void init() {
        TICKS.set(0);
        FinalJudgment.LOGGER.info("[FJ] EntityListGuard 已就绪（首建延迟 {} tick，周期 {} tick）",
                FIRST_DELAY_TICKS, REBUILD_INTERVAL_TICKS);
    }

    public static void tick() {
        int tick = TICKS.incrementAndGet();
        if (tick < FIRST_DELAY_TICKS) {
            return;
        }
        if ((tick - FIRST_DELAY_TICKS) % REBUILD_INTERVAL_TICKS != 0) {
            return;
        }
        rebuild();
    }

    private static void rebuild() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            ServerLevel level = server.overworld();
            if (level == null) {
                return;
            }
            int count = 0;
            for (Entity ignored : level.getAllEntities()) {
                count++;
            }
            lastCount = count;
            REBUILDS.incrementAndGet();
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 实体列表重建失败: {}", t.toString());
        }
    }

    public static int rebuilds() {
        return REBUILDS.get();
    }

    public static int lastCount() {
        return lastCount;
    }
}
