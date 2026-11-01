package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;
import com.maha_fish.finaljudgment.core.ShadowHealth;
import com.maha_fish.finaljudgment.core.StorageExtract;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 击杀标记表（5.2 功能 5，供 coremod 的 {@code CoremodKillTicker} 反射调用）。
 *
 * <p>30 秒超时（{@link Constants#MARK_TIMEOUT_MS}）后自动清除，避免标记泄漏。
 * coremod 通过<b>实体对象的 ClassLoader</b> 反射本类，因此方法名与签名是冻结契约：
 * {@code isMarked(Object)} / {@code forceKill(Object)}。</p>
 */
public final class KillRegistry {

    private static final Map<UUID, Long> MARKS = new ConcurrentHashMap<>();

    private KillRegistry() {
    }

    public static void mark(Object entity) {
        if (!(entity instanceof Entity living)) {
            return;
        }
        MARKS.put(living.getUUID(), System.currentTimeMillis());
        FinalJudgment.LOGGER.debug("[FJ] 已标记击杀目标: {}", living.getUUID());
    }

    public static boolean isMarked(Object entity) {
        if (!(entity instanceof Entity living)) {
            return false;
        }
        UUID id = living.getUUID();
        Long at = MARKS.get(id);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > Constants.MARK_TIMEOUT_MS) {
            MARKS.remove(id);
            return false;
        }
        return true;
    }

    /** tick 级补刀（由 coremod 注入的 Entity.tick 调用）。绝不抛异常。 */
    public static void forceKill(Object entity) {
        try {
            if (!(entity instanceof Entity living) || !isMarked(living)) {
                return;
            }
            UUID id = living.getUUID();
            ShadowHealth.set(id, 0.0f);
            if (living.level() instanceof ServerLevel level) {
                StorageExtract.extract(level, living);
            }
            MARKS.remove(id);
            FinalJudgment.LOGGER.debug("[FJ] forceKill 完成: {}", id);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] forceKill 异常: {}", t.toString());
        }
    }

    public static void purge() {
        MARKS.clear();
    }

    public static int size() {
        return MARKS.size();
    }
}
