package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 欺骗进攻（扩展 7-10）。
 *
 * <p>对**正在被我们击杀的敌方实体**伪造状态，让对方自己的反杀/自愈逻辑认为"目标已死"从而收手：</p>
 * <ul>
 *   <li>{@code isRemoved()} → true</li>
 *   <li>{@code getRemovalReason()} → KILLED</li>
 *   <li>{@code getHealth()} → 0</li>
 *   <li>{@code isAlive()} → false</li>
 * </ul>
 *
 * <p>实现层是 {@link GetterProbe}（只对**外部调用者**生效，我方代码仍读真实值）。</p>
 */
public final class BaitState {

    private static final Set<UUID> BAITED = ConcurrentHashMap.newKeySet();

    private BaitState() {
    }

    /** 标记为"假死"（欺骗进攻）。 */
    public static void markDead(net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return;
        }
        GetterProbe.set(entity, "isRemoved", Boolean.TRUE);
        GetterProbe.set(entity, "isAlive", Boolean.FALSE);
        GetterProbe.set(entity, "health", 0.0F);
        GetterProbe.set(entity, "removalReason", net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        if (BAITED.add(entity.getUUID())) {
            FinalJudgment.LOGGER.info("[FJ] 欺骗进攻: 已伪造 {} 的死亡状态（外部视角）", entity.getType());
        }
    }

    public static void clear(net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return;
        }
        BAITED.remove(entity.getUUID());
        GetterProbe.clear(entity);
    }

    public static boolean isBaited(net.minecraft.world.entity.Entity entity) {
        return entity != null && BAITED.contains(entity.getUUID());
    }

    public static int baited() {
        return BAITED.size();
    }
}