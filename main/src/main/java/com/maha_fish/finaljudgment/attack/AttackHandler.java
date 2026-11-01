package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;
import com.maha_fish.finaljudgment.core.AttackExecutor;
import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 攻击入口（7.5 功能 1）。
 *
 * <p><b>4 个入口</b>：左键空挥 / 左键点方块 / 点实体 / 攻击事件。
 * <b>左右键独立冷却 50ms</b>（两个独立计时器）。入口先查武器锚点，无锚点直接返回。</p>
 *
 * <p>Unit 9a 只做"选目标 + 去重 + 冷却 + 打日志"；真正的伤害链由 9b 接管，
 * 因此这里<b>不取消任何事件</b>（避免重演"左键打不到生物"）。</p>
 */
public final class AttackHandler {

    private static final AtomicLong LAST_LEFT = new AtomicLong();
    private static final AtomicLong LAST_RIGHT = new AtomicLong();
    /** 服务端攻击事件专用冷却（与选取包互不干扰，防止"攻击事件丢链"）。 */
    private static final AtomicLong LAST_ATTACK_EVENT = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    private AttackHandler() {
    }

    public static void onLeftClickEmpty(Player player) {
        // 空挥：客户端事件拿不到 ServerLevel，只记录意图（真正确认在 EntityInteract/AttackEntityEvent）
        if (!hasAnchor(player)) {
            return;
        }
        FinalJudgment.LOGGER.debug("[FJ] 左键空挥（无目标）");
    }

    /**
     * 统一入口：任意实体都能作为目标。
     *
     * <p>{@link LivingEntity} 走原击杀链；非生物（掉落物 / 经验球 / 箭 / 船 / TNT / 展示框）
     * 走 {@link EntityRemover} 的结构移除，并登记重生黑名单。</p>
     */
    public static boolean handleAny(Player player, net.minecraft.world.entity.Entity entity, String via, boolean force) {
        if (entity == null) {
            MISSES.incrementAndGet();
            return false;
        }
        if (entity instanceof LivingEntity living) {
            handle(player, living, via, force);
            return true;
        }
        ServerLevel level = serverLevel(player);
        if (level == null) {
            return false;
        }
        try {
            if (SpecialTargets.matches(entity)) {
                SpecialTargets.enhance(entity);
            }
        } catch (Throwable ignored) {
            // 特化失败不影响标准移除
        }
        try {
            // 扩展 7-10：先对目标伪造"已死"状态（外部视角），让对方反杀/自愈逻辑收手
            com.maha_fish.finaljudgment.defense.BaitState.markDead(entity);
        } catch (Throwable ignored) {
            // 忽略
        }
        boolean ok = KillChain.execute(level, entity);   // 与生物同一移除路线
        if (ok) {
            HITS.incrementAndGet();
        } else {
            MISSES.incrementAndGet();
        }
        if (NONLIVING_LOG.compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 非生物目标处理: {} ({}) via={} 结果={}",
                    entity.getType(), entity.getUUID(), via, ok);
        }
        return ok;
    }

    public static void onLeftClickBlock(Player player) {
        if (!hasAnchor(player) || !cooldown(LAST_LEFT)) {
            MISSES.incrementAndGet();
            return;
        }
        ServerLevel level = serverLevel(player);
        if (level == null) {
            return;
        }
        net.minecraft.world.entity.Entity target = player.isShiftKeyDown()
                ? RayTraceUtil.pickEntity(level, player, Double.MAX_VALUE,
                        com.maha_fish.finaljudgment.common.Constants.MAX_COS_ANGLE)
                : RayTraceUtil.pickEntity(level, player,
                        com.maha_fish.finaljudgment.common.Constants.MELEE_REACH,
                        com.maha_fish.finaljudgment.common.Constants.MAX_COS_ANGLE);
        handleAny(player, target, player.isShiftKeyDown() ? "Shift+左键-远程" : "左键-近战", false);
    }

    public static void onEntityInteract(Player player, LivingEntity target) {
        if (!hasAnchor(player) || !cooldown(LAST_LEFT)) {
            MISSES.incrementAndGet();
            return;
        }
        handle(player, target, "点实体", false);
    }

    public static void onAttackEntity(Player player, LivingEntity target) {
        if (player == null || !hasAnchor(player)) {
            return;
        }
        // 客户端也会收到 AttackEntityEvent，但 ClientLevel 跑不了击杀链 → 转发服务端
        if (!(player.level() instanceof ServerLevel)) {
            AttackPickPacket.send((byte) 0);
            return;
        }
        // 服务端用独立冷却：否则选取包刚消耗掉左键冷却，这里会把链丢掉
        if (!cooldown(LAST_ATTACK_EVENT)) {
            MISSES.incrementAndGet();
            return;
        }
        handle(player, target, "攻击事件", false);
    }

    /** 远程入口（右键）：范围 {@link Constants#REMOTE_DISTANCE} = 64。 */
    /** 右键：范围攻击（AABB 扫描周围所有实体，半径 RANGE_RADIUS = 8）。 */
    public static void onRangeAttack(Player player) {
        if (!hasAnchor(player) || !cooldown(LAST_RIGHT)) {
            return;
        }
        ServerLevel level = serverLevel(player);
        if (level == null) {
            return;
        }
        double radius = com.maha_fish.finaljudgment.common.Constants.RANGE_RADIUS;
        java.util.List<net.minecraft.world.entity.Entity> targets = new java.util.ArrayList<>();
        String source = "注册表";

        // 主扫描：注册表（含隐藏实体 / tick 捕捉），距离严格 ≤ radius
        try {
            for (net.minecraft.world.entity.Entity entity : EntityRegistry.candidates(level)) {
                if (entity == null || entity == player || entity.isRemoved()) {
                    continue;
                }
                if (inRange(player, entity, radius)) {
                    targets.add(entity);
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 注册表扫描失败: {}", t.toString());
        }

        // 兜底①：主扫描一个都没扫到 → AABB 扫描（半径与主扫描一致）
        if (targets.isEmpty()) {
            source = "AABB";
            try {
                for (net.minecraft.world.entity.Entity entity :
                        level.getEntities(player, player.getBoundingBox().inflate(radius))) {
                    if (entity == null || entity == player || entity.isRemoved()) {
                        continue;
                    }
                    if (inRange(player, entity, radius)) {
                        targets.add(entity);
                    }
                }
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] AABB 扫描失败: {}", t.toString());
            }
        }


        int hit = 0;
        for (net.minecraft.world.entity.Entity entity : targets) {
            if (handleAny(player, entity, "右键-范围", true)) {
                hit++;
            }
        }
        if (RANGE_LOG.compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 右键范围攻击: 命中 {} / 目标 {} 个（半径 {}，来源={}）",
                    hit, targets.size(), radius, source);
        }
        if (hit > 0) {
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        }
    }

    /** 距离判定（统一口径：只在 ≤ radius 时算命中）。 */
    private static boolean inRange(Player player, net.minecraft.world.entity.Entity entity, double radius) {
        try {
            return player.position().distanceTo(entity.position()) <= radius;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean NONLIVING_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final java.util.concurrent.atomic.AtomicBoolean RANGE_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 远程（Shift+左键）：按视线在 REMOTE_DISTANCE 内选单个目标。 */
    public static void onRemote(Player player) {
        if (!hasAnchor(player) || !cooldown(LAST_RIGHT)) {
            return;
        }
        ServerLevel level = serverLevel(player);
        if (level == null) {
            return;
        }
        net.minecraft.world.entity.Entity picked = RayTraceUtil.pickEntity(level, player,
                com.maha_fish.finaljudgment.common.Constants.REMOTE_DISTANCE,
                com.maha_fish.finaljudgment.common.Constants.MAX_COS_ANGLE);
        handleAny(player, picked, "Shift+左键-远程", true);
    }

    /** 服务端回调：客户端请求选目标，服务端已完成判定。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RIGHT_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final java.util.concurrent.atomic.AtomicBoolean ACQUIRE_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final java.util.concurrent.atomic.AtomicBoolean PICK_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final java.util.concurrent.atomic.AtomicBoolean NO_ANCHOR_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void onPickResult(Player player, LivingEntity target, byte mode) {
        if (!hasAnchor(player)) {
            if (NO_ANCHOR_LOGGED.compareAndSet(false, true)) {
                FinalJudgment.LOGGER.info("[FJ] 选取包已到达但主手无锚点武器，忽略（首次）");
            }
            return;
        }
        String via = switch (mode) {
            case 1 -> "Shift+左键";
            case 2 -> "右键-远程";
            default -> "左键-近战";
        };
        if (PICK_LOG.compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 选取结果 mode={} target={} 距离={}",
                    mode, target == null ? "null" : target.getUUID(),
                    target == null ? -1 : Math.round(player.distanceTo(target) * 10) / 10.0);
        }
        handle(player, target, via, false);
    }

    private static void handle(Player player, LivingEntity target, String via, boolean force) {
        if (target == null) {
            MISSES.incrementAndGet();
            return;
        }
        long tick = player.level().getGameTime();
        try {
            com.maha_fish.finaljudgment.client.RenderProtector.protect(target, 2000L);
        } catch (Throwable ignored) {
            // 专用服务端没有客户端类，忽略
        }
        boolean acquired = force
                ? AttackExecutor.forceAcquire(player, target)
                : AttackExecutor.tryAcquire(tick, player, target);
        if (!acquired) {
            if (ACQUIRE_LOG.compareAndSet(false, true)) {
                FinalJudgment.LOGGER.info("[FJ] 命中被去重/冷却拦下（首次）: target={}", target.getUUID());
            }
            return;
        }
        HITS.incrementAndGet();
        // 日志瘦身：命中不再逐次输出
        // 锁定目标：后续补刀只跟它，绝不重新选目标（否则会补错对象）
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ServerAutoAttack.commitTarget(serverPlayer, target);
        }
        // 9d：把目标加入追踪表（5 tick），期间检测瞬移并重新登记
        if (player.level() instanceof ServerLevel level) {
            TrackingAttack.track(level, target);
        }
        // Unit 9b：进入击杀链（默认全路径，-Dfj.kill.allPaths=false 改为见好就收）
        if (player.level() instanceof ServerLevel level) {
            KillChain.execute(level, target);
        }
    }

    private static boolean hasAnchor(Player player) {
        // 只认主手：武器放在背包里但空手时必须不生效
        return player != null && T0Anchor.hasAnchorInMainHand(player);
    }

    private static boolean cooldown(AtomicLong last) {
        long now = System.currentTimeMillis();
        long previous = last.get();
        if (now - previous < Constants.ATTACK_COOLDOWN_MS) {
            return false;
        }
        last.set(now);
        return true;
    }

    private static ServerLevel serverLevel(Player player) {
        return player != null && player.level() instanceof ServerLevel level ? level : null;
    }

    public static long hits() {
        return HITS.get();
    }

    public static long misses() {
        return MISSES.get();
    }
}
