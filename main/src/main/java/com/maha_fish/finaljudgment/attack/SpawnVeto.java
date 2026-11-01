package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实体重生拦截（对应 ArcaneVortex 的 Agent/EntitySpawn/EntitySpawnInterceptor）。
 *
 * <p>解决"会重启的实体"：某些实体被删掉后会由自己的 AI / 事件 / 定时器重新 addEntity 回来。
 * 我们把它被击杀时的 UUID 记进永久黑名单，并在 {@code Level.addFreshEntity} 入口处直接否决。</p>
 */
public final class SpawnVeto {

    private static final Set<UUID> BLOCKED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean LOG = new AtomicBoolean(false);

    private SpawnVeto() {
    }

    /** 把实体加入"拒绝重生"名单。 */
    public static void block(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            BLOCKED.add(entity.getUUID());
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static boolean isBlocked(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            return BLOCKED.contains(entity.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供 Mixin 调用：true 表示应否决这次加入世界。 */
    public static boolean shouldVeto(Entity entity) {
        boolean veto = isBlocked(entity);
        if (veto && LOG.compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 已拦截被击杀实体的重生: {} ({})",
                    entity.getType(), entity.getUUID());
        }
        return veto;
    }

    /** 服务端清扫：把黑名单里"已经混回世界"的实体重新移除（每 20 tick 调一次）。 */
    public static void sweep(net.minecraft.server.level.ServerLevel level) {
        if (level == null || BLOCKED.isEmpty()) {
            return;
        }
        int removed = 0;
        try {
            for (net.minecraft.world.entity.Entity entity : EntityRegistry.candidates(level)) {
                if (entity != null && BLOCKED.contains(entity.getUUID())) {
                    if (EntityRemover.remove(level, entity)) {
                        removed++;
                    }
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] SpawnVeto.sweep 异常: {}", t.toString());
        }
        if (removed > 0 && LOG.compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 清扫黑名单实体: {} 个", removed);
        }
    }

    public static int size() {
        return BLOCKED.size();
    }

    public static void clear() {
        BLOCKED.clear();
    }
}