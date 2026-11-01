package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪攻击（7.5 功能 10）。
 *
 * <p>{@link #TRACK_DURATION_TICKS} = 5：登记后连续 5 tick 重新定位目标并重跑击杀链。
 * 追踪表每 tick 递减 {@code remainingTicks}，归零即移除。</p>
 */
public final class TrackingAttack {

    public static final int TRACK_DURATION_TICKS = 5;

    private static final Map<UUID, Tracked> TRACKED = new ConcurrentHashMap<>();

    private TrackingAttack() {
    }

    /** 登记/刷新一个追踪目标。 */
    public static void track(ServerLevel level, Entity target) {
        if (level == null || target == null) {
            return;
        }
        TRACKED.put(target.getUUID(), new Tracked(level, target, TRACK_DURATION_TICKS));
    }

    /** 每 tick：递减并在未消失时重定位 + 重跑链。 */
    public static void tick() {
        if (TRACKED.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<UUID, Tracked> entry : TRACKED.entrySet()) {
                Tracked tracked = entry.getValue();
                if (tracked.remainingTicks-- <= 0) {
                    TRACKED.remove(entry.getKey());
                    continue;
                }
                Entity current = tracked.level.getEntity(tracked.entity.getId());
                if (current == null || KillValidator.isGone(tracked.entity)) {
                    TRACKED.remove(entry.getKey());
                    continue;
                }
                // 9d：目标瞬移（位置/速度异常）→ 重新登记追踪，保证"瞬移仍能命中"
                DefenseTicker.antiTeleportTarget(tracked.level, current);
                if (current instanceof LivingEntity living) {
                    KillChain.execute(tracked.level, living);
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] TrackingAttack 异常: {}", t.toString());
        }
    }

    public static int size() {
        return TRACKED.size();
    }

    private static final class Tracked {
        final ServerLevel level;
        final Entity entity;
        int remainingTicks;

        Tracked(ServerLevel level, Entity entity, int remainingTicks) {
            this.level = level;
            this.entity = entity;
            this.remainingTicks = remainingTicks;
        }
    }
}
