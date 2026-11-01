package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 防御层 tick（规范 37 的持有者侧；Unit 10 会在此扩展防缴械）。
 *
 * <p><b>闸门语义（用户要求）</b>：防御相关功能只要<b>背包里有锚点武器</b>就生效，
 * 不要求拿在手上（与"攻击必须主手持武器"是两套闸门）。</p>
 *
 * <p>反传送：位置异常 → 恢复到上一次已知位置 + 重新登记追踪。</p>
 */
public final class DefenseTicker {

    private static final AtomicInteger RESTORED = new AtomicInteger();
    /** 是否有玩家处于"持有武器"状态（防御是否真正在线）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean ARMED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static boolean armed() {
        return ARMED.get();
    }
    private static final AtomicInteger CHECKS = new AtomicInteger();

    private DefenseTicker() {
    }

    public static void tick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        try {
            // 先确定"持有者"（多玩家时只认第一个持有武器的），避免被未持武器者清空
            ServerPlayer holder = null;
            for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
                if (T0Anchor.hasAnchorInInventory(candidate)) { holder = candidate; break; }
            }
            com.maha_fish.finaljudgment.KillFlagBridge.setProtectedPlayer(holder);
        try {
              // 对所有持锚点玩家都执行：Deception 激活/同步 + 死亡状态复位（不再只 holder 一人）
              for (ServerPlayer guarded : server.getPlayerList().getPlayers()) {
                  if (!T0Anchor.hasAnchorInInventory(guarded)) {
                      continue;
                  }
                  if (!com.maha_fish.finaljudgment.defense.DeceptionStore.isActive(guarded)) {
                      com.maha_fish.finaljudgment.defense.DeceptionStore.activate(guarded,
                              Math.max(1.0F, guarded.getMaxHealth() * 0.5F));
                  } else {
                      com.maha_fish.finaljudgment.defense.DeceptionStore.refreshReal(guarded);
                  }
                  // 防御侧死亡状态复位（倒地/白烟）：每 tick 无条件执行
                  com.maha_fish.finaljudgment.attack.DeadState.guardTick(guarded);
              }
        } catch (Throwable ignored) {
            // 欺骗层失败不影响防御
        }
            ARMED.set(holder != null);
            if (holder == null) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!T0Anchor.hasAnchorInInventory(player)) {
                    continue;                 // 背包里没有锚点武器 → 防御功能对该玩家不生效
                }
                CHECKS.incrementAndGet();
                antiTeleport(player);
                enforceHealth(player);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] DefenseTicker 异常: {}", t.toString());
        }
        try {
            com.maha_fish.finaljudgment.guard.SelfGuard.touch();   // 首次调用建立关键类基线
            com.maha_fish.finaljudgment.WiringAudit.touch();       // 接线自检（只打印一次）
            com.maha_fish.finaljudgment.guard.PeriodicRetransformer.touch();   // 周期性重应用（60s）
            com.maha_fish.finaljudgment.guard.DefenseScanner.scanPeriodic();  // 防守扫描（30s 补扫）
        } catch (Throwable ignored) {
            // 自保护失败不影响防御
        }
    }

    /** 每 tick 强制回血到基线（对抗"绕过 hurt 直写血量/直接置死"的攻击方式）。 */
    private static void enforceHealth(ServerPlayer player) {
        try {
            float baseline = com.maha_fish.finaljudgment.core.ShadowHealth.getBaseline(
                    player.getUUID(), player.getMaxHealth());
            if (baseline <= 0.0F) {
                baseline = player.getMaxHealth();
            }
            // 每 tick 强制同步血条（避免服务端满血、客户端显示 0 的假血条）
            try {
                com.maha_fish.finaljudgment.core.SynchedEntityDataWriter.writeHealth(player, player.getHealth());
            } catch (Throwable ignored) {
                // 忽略
            }
            if (player.getHealth() < baseline || player.isDeadOrDying()) {
                com.maha_fish.finaljudgment.core.ShadowHealth.set(player.getUUID(), baseline);
                player.setHealth(baseline);
                com.maha_fish.finaljudgment.core.SynchedEntityDataWriter.writeHealth(player, baseline);
                // 注意：dead 是 boolean 字段，不能用 writeInt 直写（会写坏内存），
                // setHealth(>0) 本身就会让原版把 dead 复位，这里不再额外触碰。
                FinalJudgment.LOGGER.debug("[FJ] 强制回血到基线 {}: {}", baseline, player.getUUID());
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] enforceHealth 失败: {}", t.toString());
        }
    }

    /** 反传送：检测到异常就把玩家拉回上一个合法位置（500ms 冷却，避免与确认包互相打架）。 */
    private static final java.util.Map<java.util.UUID, Long> LAST_RESTORE = new java.util.concurrent.ConcurrentHashMap<>();

    private static void antiTeleport(ServerPlayer player) {
        UUID id = player.getUUID();
        long nowMs = System.currentTimeMillis();
        Long lastMs = LAST_RESTORE.get(id);
        if (lastMs != null && nowMs - lastMs < 500L) {
            return;
        }
        Vec3 last = PositionGuard.lastKnown(id);
        boolean abnormal = PositionGuard.detect(player);
        if (!abnormal || last == null) {
            return;
        }
        try {
            if (PositionRestorer.restore(player, last)) {
                RESTORED.incrementAndGet();
                LAST_RESTORE.put(id, System.currentTimeMillis());
                // 关键：服务端权威位置必须通过位置包强制同步给客户端，
                // 否则客户端确认包（AcceptTeleportation）会把位置盖回传送点，表现为"日志有、画面没变"
                if (player.connection != null) {
                    player.connection.teleport(last.x, last.y, last.z, player.getYRot(), player.getXRot());
                }
                FinalJudgment.LOGGER.info("[FJ] 反传送: 已恢复持有者位置 {} → ({}, {}, {})",
                        id, Math.round(last.x), Math.round(last.y), Math.round(last.z));
            }
            if (player.level() instanceof ServerLevel level) {
                RetrackService.retrack(level, player);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 反传送恢复失败: {}", t.toString());
        }
    }

    /** 目标侧反传送（由 TrackingAttack 调用）：瞬移后仍保持追踪。 */
    public static void antiTeleportTarget(ServerLevel level, Entity target) {
        if (level == null || target == null) {
            return;
        }
        if (PositionGuard.detect(target)) {
            RetrackService.retrack(level, target);
            FinalJudgment.LOGGER.debug("[FJ] 反传送: 目标瞬移，已重新登记追踪 {}", target.getUUID());
        }
    }

    public static int restored() {
        return RESTORED.get();
    }

    public static int checks() {
        return CHECKS.get();
    }
}
