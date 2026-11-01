package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.common.Constants;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 视线选取（7.5 功能 3）。
 *
 * <p>硬约束：用 <b>{@code entity.position()}</b>（不是 {@code getBoundingBox()}）；
 * 视线夹角 <b>cos &gt; 0.80</b>；候选集先按 {@link Constants#RANGE_RADIUS} 粗筛。</p>
 *
 * <p>三档：近战 {@link Constants#MELEE_REACH}、远程 {@link Constants#REMOTE_DISTANCE}（64）、
 * 无限距离（Shift+左键，只按夹角选）。</p>
 */
public final class RayTraceUtil {

    private RayTraceUtil() {
    }

    public static LivingEntity pickMelee(ServerLevel level, Player player) {
        return pick(level, player, Constants.MELEE_REACH, Constants.MAX_COS_ANGLE);
    }

    public static LivingEntity pickRemote(ServerLevel level, Player player) {
        return pick(level, player, Constants.REMOTE_DISTANCE, Constants.MAX_COS_ANGLE);
    }

    /** Shift+左键：无距离上限，只按视线夹角选（cos 阈值与普通左键一致）。 */
    public static LivingEntity pickUnlimited(ServerLevel level, Player player) {
        return pick(level, player, Double.MAX_VALUE, Constants.MAX_COS_ANGLE);
    }

    /**
     * @param maxDistance 距离上限（{@code Double.MAX_VALUE} 表示无限）
     * @param minCos      视线夹角余弦阈值（越大越严格）
     */
    /**
     * 右键范围攻击的选取：沿准星方向、{@link Constants#REMOTE_DISTANCE} 距离内、
     * 夹角余弦 ≥ {@link Constants#MAX_COS_ANGLE} 的**所有**存活实体（多目标，不是单个）。
     *
     * <p>另外兜底：若扇形里一个都没有，则取玩家 {@link Constants#RANGE_RADIUS} 内的全部实体，
     * 保证贴脸右键也有反馈。</p>
     */
    public static java.util.List<LivingEntity> pickRange(ServerLevel level, Player player) {
        java.util.List<LivingEntity> result = new java.util.ArrayList<>();
        if (level == null || player == null) {
            return result;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        try {
            for (Entity entity : EntityRegistry.candidates(level)) {
                if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive()) {
                    continue;
                }
                if (living instanceof Player) {
                    continue;                       // 不误伤其他玩家
                }
                double distance;
                try {
                    distance = living.distanceTo(player);
                } catch (Throwable t) {
                    distance = eye.distanceTo(living.position());
                }
                if (distance > Constants.REMOTE_DISTANCE) {
                    continue;
                }
                Vec3 dir = living.position().subtract(eye);
                double length = dir.length();
                if (length < 1.0E-4D) {
                    continue;
                }
                double cos = look.dot(dir.scale(1.0D / length));
                if (cos >= Constants.MAX_COS_ANGLE) {
                    result.add(living);
                    continue;
                }
                // 贴脸兜底：极近距离不看夹角
                if (distance <= Constants.RANGE_RADIUS) {
                    result.add(living);
                }
            }
        } catch (Throwable t) {
            return result;
        }
        return result;
    }

    /**
     * 任意实体选取（不限定 LivingEntity）：掉落物 / 经验球 / 箭 / 船 / TNT / 展示框 等都能选中。
     *
     * <p>规则与 {@link #pick} 一致：距离 ≤ maxDistance、视线夹角余弦 ≥ minCos、排除玩家自己。</p>
     */
    public static Entity pickEntity(ServerLevel level, Player player, double maxDistance, double minCos) {
        if (level == null || player == null) {
            return null;
        }
        com.maha_fish.finaljudgment.defense.SelfContext.enter();   // 我方查询：不被隐藏逻辑影响
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Entity best = null;
        double bestCos = minCos;
        try {
            for (Entity entity : EntityRegistry.candidates(level)) {
                if (entity == null || entity == player || entity instanceof Player || entity.isRemoved()) {
                    continue;
                }
                double distance;
                try {
                    distance = entity.distanceTo(player);
                } catch (Throwable t) {
                    distance = eye.distanceTo(entity.position());
                }
                if (maxDistance != Double.MAX_VALUE && distance > maxDistance) {
                    continue;
                }
                Vec3 dir = entity.position().subtract(eye);
                double length = dir.length();
                if (length < 1.0E-4D) {
                    continue;
                }
                double cos = look.dot(dir.scale(1.0D / length));
                if (cos > bestCos) {
                    bestCos = cos;
                    best = entity;
                }
            }
        } catch (Throwable t) {
            return best;
        } finally {
            com.maha_fish.finaljudgment.defense.SelfContext.exit();
        }
        // 兜底：候选集为空时用原版射线（AABB 命中）再试一次
        if (best == null && maxDistance != Double.MAX_VALUE) {
            try {
                net.minecraft.world.phys.HitResult hit = player.pick(maxDistance, 1.0F, false);
                if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit
                        && entityHit.getEntity() != player) {
                    best = entityHit.getEntity();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        return best;
    }

    public static LivingEntity pick(ServerLevel level, Player player, double maxDistance, double minCos) {
        if (level == null || player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        LivingEntity best = null;
        double bestCos = minCos;
        try {
            // 关键：候选集 = getAllEntities() ∪ 登记表（隐藏实体只能从登记表拿到）
            for (Entity entity : EntityRegistry.candidates(level)) {
                if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive()) {
                    continue;
                }
                // 位置判定：entity.position()（规范要求，不用 boundingBox）
                Vec3 target = living.position();
                double distance;
                try {
                    distance = living.distanceTo(player);
                } catch (Throwable t) {
                    distance = eye.distanceTo(target);
                }
                // 粗筛：无限距离时也限制在同一维度内（level 已保证），这里只做有效值检查
                if (maxDistance != Double.MAX_VALUE && distance > maxDistance) {
                    continue;
                }
                Vec3 dir = target.subtract(eye);
                double length = dir.length();
                if (length < 1.0E-4D) {
                    continue;
                }
                double cos = look.dot(dir.scale(1.0D / length));
                if (cos > bestCos) {
                    bestCos = cos;
                    best = living;
                }
            }
        } catch (Throwable t) {
            return best;
        }
        return best;
    }

    /** 视线夹角余弦（供诊断）。 */
    public static double cosAngle(Player player, Entity target) {
        if (player == null || target == null) {
            return -1.0D;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 dir = target.position().subtract(eye);
        double length = dir.length();
        if (length < 1.0E-4D) {
            return -1.0D;
        }
        return player.getLookAngle().dot(dir.scale(1.0D / length));
    }
}
