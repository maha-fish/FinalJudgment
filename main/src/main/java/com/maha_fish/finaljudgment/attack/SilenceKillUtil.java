package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静默击杀（7.5 功能 7）：委托 {@link KillChain}，并登记"静默目标"。
 *
 * <p>3 条守护逻辑：抑制死亡音效 / 战利品 / 经验（由 9d 的 tick 守护消费）。
 * 静默表**必须有上限清理**，否则长期运行会泄漏 UUID。</p>
 */
public final class SilenceKillUtil {

    private static final Set<UUID> SILENCED = ConcurrentHashMap.newKeySet();
    private static final int MAX_SILENCED = 512;

    private SilenceKillUtil() {
    }

    /** 静默击杀：先登记，再走击杀链。 */
    public static boolean kill(ServerLevel level, Entity target) {
        if (level == null || target == null) {
            return false;
        }
        if (SILENCED.size() > MAX_SILENCED) {
            SILENCED.clear();
        }
        SILENCED.add(target.getUUID());
        boolean gone = KillChain.execute(level, target);
        if (!gone) {
            SILENCED.remove(target.getUUID());
        }
        FinalJudgment.LOGGER.debug("[FJ] 静默击杀: {} gone={}", target.getUUID(), gone);
        return gone;
    }

    public static boolean isSilenced(Entity entity) {
        return entity != null && SILENCED.contains(entity.getUUID());
    }

    // ===== 3 条守护逻辑（由 9d 在对应事件/注入点消费） =====

    public static boolean suppressDeathSound(Entity entity) {
        return isSilenced(entity);
    }

    public static boolean suppressLoot(Entity entity) {
        return isSilenced(entity);
    }

    public static boolean suppressXp(Entity entity) {
        return isSilenced(entity);
    }

    public static void clear() {
        SILENCED.clear();
    }

    public static int size() {
        return SILENCED.size();
    }
}
