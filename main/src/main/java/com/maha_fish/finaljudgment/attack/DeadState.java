package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.FieldWriter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 死亡状态收尾（参考项目 {@code DeadFieldAccessor} / {@code HealthFieldUtil.setDeadFlag} 的等价实现）。
 *
 * <p>用于解决"实体倒地、一直冒白烟、不消失"的半死状态：</p>
 * <ul>
 *   <li>{@code deathTime} 归零</li>
 *   <li>{@code dead} 标志归 false（Unsafe 直写，绕开 private）</li>
 * </ul>
 *
 * <p>这样即便结构移除被对方的防御拦下，实体也回到"活的、干净"的状态，
 * 不会卡在死亡动画里循环冒烟。</p>
 */
public final class DeadState {

    /** Entity.dead 字段候选（MCP + SRG）。 */
    private static final String[] DEAD_FIELD = {"dead", "f_19835_"};

    private DeadState() {
    }

    /** 是否处于"倒地/正在死亡"状态。 */
    public static boolean isDying(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        try {
            return entity.deathTime > 0 || entity.isDeadOrDying();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 清理死亡状态：deathTime = 0 + dead = false。
     *
     * @return true 表示至少一项被清理
     */
    public static boolean reset(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        boolean cleaned = false;
        try {
            if (entity.deathTime != 0) {
                entity.deathTime = 0;
                cleaned = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            long offset = FieldWriter.offsetOf(Entity.class, DEAD_FIELD, "boolean");
            if (offset >= 0 && FieldWriter.writeBoolean(entity, offset, false)) {
                cleaned = true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            // 同时抹掉客户端会读到的"死亡"标记来源
            entity.setHealth(Math.max(entity.getHealth(), 1.0F));
        } catch (Throwable ignored) {
            // 忽略
        }
        if (cleaned) {
            FinalJudgment.LOGGER.info("[FJ] 已清理死亡状态（避免倒地冒烟循环）: {}", entity.getType());
        }
        return cleaned;
    }
    /**
     * 防御侧复位（参考项目 {@code DefenseHandler} / {@code HeartbeatThread} 的做法）。
     *
     * <p>每 tick 对持有者调用：一旦出现 {@code deathTime > 0} / {@code isDeadOrDying()} /
     * {@code isRemoved()}，立即</p>
     * <ol>
     *   <li>{@code deathTime = 0}、{@code dead = false}（消除倒地与白烟）</li>
     *   <li>把血量恢复到影子血量（{@code ShadowHealth}），不低于 1</li>
     *   <li>清理 {@code removalReason}（用 Unsafe 直写为 null，避免被判为已移除）</li>
     * </ol>
     *
     * @return true 表示本次做了复位
     */
    public static boolean guardTick(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) {
            return false;
        }
        boolean dying;
        try {
            dying = player.deathTime > 0 || player.isDeadOrDying() || player.isRemoved();
        } catch (Throwable t) {
            return false;
        }
        if (!dying) {
            return false;
        }
        try {
            player.deathTime = 0;
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            long offset = FieldWriter.offsetOf(Entity.class, DEAD_FIELD, "boolean");
            if (offset >= 0) {
                FieldWriter.writeBoolean(player, offset, false);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            // 清 removalReason：否则 isRemoved() 仍为 true，玩家会被判定"已移除"
            long reasonOffset = FieldWriter.offsetOf(Entity.class,
                    new String[]{"removalReason", "f_146795_"}, "RemovalReason");
            if (reasonOffset >= 0) {
                FieldWriter.writeObject(player, reasonOffset, null);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            float shadow = com.maha_fish.finaljudgment.core.ShadowHealth.get(player.getUUID(),
                    player.getMaxHealth());
            float target = Math.min(player.getMaxHealth(), Math.max(1.0F, shadow));
            if (player.getHealth() < target) {
                player.setHealth(target);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        FinalJudgment.LOGGER.info("[FJ] 防御复位持有者（倒地/白烟清除）: hp={} deathTime={}",
                player.getHealth(), player.deathTime);
        return true;
    }
}