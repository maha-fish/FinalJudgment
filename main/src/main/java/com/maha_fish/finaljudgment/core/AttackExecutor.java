package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 攻击执行器（7.9 扩展 13）。
 *
 * <p>双入口：客户端 tick 与服务端 `LivingEntity.tick`（注入点在 11b）。
 * <b>同一 tick 内同一目标只处理一次</b>（已处理集合，按 tick 号自动清空），
 * 并有 `ATTACK_COOLDOWN_MS` 冷却。[FJ] 7c 只落帧结构与防重复，具体攻击动作由 9a/9b 提供。</p>
 */
public final class AttackExecutor {

    private static final Set<UUID> PROCESSED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong LAST_CLEAR_TICK = new AtomicLong(-1);
    private static final AtomicLong LAST_ATTACK_MS = new AtomicLong();

    private AttackExecutor() {
    }

    public static void init() {
        FinalJudgment.LOGGER.info("[FJ] AttackExecutor 已就绪（冷却 {} ms）", Constants.ATTACK_COOLDOWN_MS);
    }

    /** 客户端入口（每 tick）：回合边界清空已处理集合。 */
    public static void beginTick(long tick) {
        if (LAST_CLEAR_TICK.getAndSet(tick) != tick) {
            PROCESSED.clear();
        }
    }

    /**
     * 尝试对目标执行一次攻击动作。
     *
     * @return true 表示本 tick 首次处理该目标且通过冷却检查
     */
    public static boolean tryAcquire(long tick, Player source, LivingEntity target) {
        if (target == null || source == null) {
            return false;
        }
        beginTick(tick);
        UUID id = target.getUUID();
        if (!PROCESSED.add(id)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = LAST_ATTACK_MS.get();
        if (now - last < Constants.ATTACK_COOLDOWN_MS) {
            return false;
        }
        LAST_ATTACK_MS.set(now);
        return true;
    }

    /** 强制取得命中权：跳过 tick 去重与全局冷却（右键远程专用，避免被自动攻击挤掉）。 */
    public static boolean forceAcquire(Player source, LivingEntity target) {
        if (target == null || source == null) {
            return false;
        }
        PROCESSED.add(target.getUUID());
        LAST_ATTACK_MS.set(System.currentTimeMillis());
        return true;
    }

    public static int processed() {
        return PROCESSED.size();
    }
}
