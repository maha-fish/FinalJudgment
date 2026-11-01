package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 隔离状态管理（7.4.5 功能 1，规范 18）。
 *
 * <p>与 {@link FieldIsolation} / {@link ShadowHealth} 同步；<b>登出必须清理</b>，
 * 否则 UUID 永久残留（同类风险见规范 18）。每 tick 只做一次轻量校验。</p>
 */
public final class IsolationManager {

    private static final Set<UUID> ISOLATED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static boolean initialised;

    private IsolationManager() {
    }

    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        FinalJudgment.LOGGER.info("[FJ] IsolationManager 已就绪");
    }

    public static void isolate(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        ISOLATED.add(id);
        FieldIsolation.isolate(id);
        // 首帧把当前血量写成"三基线"的起点
        ShadowHealth.set(id, player.getHealth());
        ShadowHealth.setMax(id, player.getMaxHealth());
    }

    public static void release(Player player) {
        if (player == null) {
            return;
        }
        release(player.getUUID());
    }

    public static void release(UUID id) {
        ISOLATED.remove(id);
        FieldIsolation.release(id);
        ShadowHealth.remove(id);
    }

    /** 每 tick：仅校验已隔离玩家（数量极少，成本可忽略）。 */
    public static void tick() {
        if (ISOLATED.isEmpty()) {
            return;
        }
        CHECKS.incrementAndGet();
        for (UUID id : ISOLATED) {
            try {
                ShadowHealth.verifyAndRepair(id);
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] 隔离校验异常 {}: {}", id, t.toString());
            }
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event == null) {
            return;
        }
        // getEntity() 的静态类型已经是 Player，instanceof 模式变量会被判为"必然匹配"
        Player player = event.getEntity();
        if (player == null) {
            return;
        }
        release(player);
        FinalJudgment.LOGGER.debug("[FJ] 登出清理隔离状态: {}", player.getUUID());
    }

    public static int size() {
        return ISOLATED.size();
    }

    public static int checks() {
        return CHECKS.get();
    }
}
