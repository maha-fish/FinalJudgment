package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 伤害拦截（7.6，规范 26）。
 *
 * <p>闸门：<b>背包里任意位置有锚点武器即生效</b>（用户要求：防御不要求拿在手上）。</p>
 * <p>反制哨兵：同一攻击者在 {@link #COUNTER_ATTACK_WINDOW_MS} 内命中
 * {@link #COUNTER_ATTACK_THRESHOLD} 次 → 记录为反制目标（由 9b 的链后续处理）。</p>
 */
public final class DefenseHandler {

    public static final int COUNTER_ATTACK_THRESHOLD = 3;
    public static final long COUNTER_ATTACK_WINDOW_MS = 5000L;

    private static final Map<UUID, long[]> HITS = new ConcurrentHashMap<>();   // attacker -> {count, firstMs}
    private static final Map<UUID, UUID> COUNTER_TARGETS = new ConcurrentHashMap<>();
    private static final AtomicInteger BLOCKED = new AtomicInteger();
    private static final AtomicInteger COUNTERS = new AtomicInteger();

    /** Forge 事件总线实例化。 */
    public DefenseHandler() {
    }

    public static void init() {
        FinalJudgment.LOGGER.info("[FJ] DefenseHandler 已就绪（反制 {} 次/{}ms，闸门=背包有锚点武器）",
                COUNTER_ATTACK_THRESHOLD, COUNTER_ATTACK_WINDOW_MS);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (event == null || !(event.getEntity() instanceof Player player)) {
            return;
        }
        try {
            if (T0Anchor.hasAnchorInInventory(player)) {
                event.setCanceled(true);       // 最早一道：连伤害计算都不进入
                counterAttack(player, event.getSource().getEntity());
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] LivingAttackEvent 拦截异常: {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (event == null || !(event.getEntity() instanceof Player player)) {
            return;
        }
        try {
            if (T0Anchor.hasAnchorInInventory(player)) {
                event.setAmount(0.0F);
                event.setCanceled(true);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] LivingDamageEvent 拦截异常: {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onHurt(LivingHurtEvent event) {
        if (event == null || !(event.getEntity() instanceof Player player)) {
            return;
        }
        try {
            if (!T0Anchor.hasAnchorInInventory(player)) {
                return;                       // 没有武器 → 正常受伤
            }
            event.setCanceled(true);          // 取消后原版不会扣血
            event.setAmount(0.0F);
            BLOCKED.incrementAndGet();
            counterAttack(player, event.getSource().getEntity());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 伤害拦截异常: {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onDeath(LivingDeathEvent event) {
        if (event == null || !(event.getEntity() instanceof Player player)) {
            return;
        }
        try {
            if (T0Anchor.hasAnchorInInventory(player)) {
                event.setCanceled(true);
                BLOCKED.incrementAndGet();
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 死亡拦截异常: {}", t.toString());
        }
    }

    /** 反制哨兵：窗口内命中 3 次即登记为反制目标。 */
    private static void counterAttack(Player holder, net.minecraft.world.entity.Entity attacker) {
        if (attacker == null) {
            return;
        }
        UUID attackerId = attacker.getUUID();
        long now = System.currentTimeMillis();
        long[] record = HITS.computeIfAbsent(attackerId, k -> new long[]{0L, now});
        if (now - record[1] > COUNTER_ATTACK_WINDOW_MS) {
            record[0] = 0L;
            record[1] = now;
        }
        record[0]++;
        if (record[0] >= COUNTER_ATTACK_THRESHOLD) {
            COUNTER_TARGETS.put(holder.getUUID(), attackerId);
            record[0] = 0L;
            record[1] = now;
            COUNTERS.incrementAndGet();
            FinalJudgment.LOGGER.info("[FJ] 反制哨兵触发: {} 连续命中 {} 次 → 标记反制目标 {}",
                    attacker.getUUID(), COUNTER_ATTACK_THRESHOLD, holder.getUUID());
            // 真正的反制动作：立即对该攻击者执行击杀链（否则"只记录不动作"，实测就是这样）
            counterKill(holder, attacker);
        }
    }

    /** 反制执行：把攻击者送进击杀链（静默，避免额外战利品刷屏）。 */
    private static void counterKill(Player holder, net.minecraft.world.entity.Entity attacker) {
        try {
            if (!(attacker instanceof net.minecraft.world.entity.LivingEntity living)
                    || attacker == holder
                    || !attacker.isAlive()) {
                return;
            }
            if (!(holder.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                return;
            }
            com.maha_fish.finaljudgment.attack.SilenceKillUtil.kill(level, living);
            FinalJudgment.LOGGER.info("[FJ] 反制已执行: 攻击者 {} 已进入击杀链", attacker.getUUID());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] 反制执行失败: {}", t.toString());
        }
    }

    public static UUID counterTarget(UUID holder) {
        return COUNTER_TARGETS.get(holder);
    }

    public static void clearCounter(UUID holder) {
        COUNTER_TARGETS.remove(holder);
    }

    public static int blocked() {
        return BLOCKED.get();
    }

    public static int counters() {
        return COUNTERS.get();
    }
}
