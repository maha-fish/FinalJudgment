package com.maha_fish.finaljudgment.core;

import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隔离状态表（7.4.2 功能 1，规范 18）。
 *
 * <p><b>规范 18 的硬约束</b>：{@link #isIsolated(Object)} 入口先判 {@code instanceof Player}，
 * 非玩家直接 false。否则某个怪物 UUID 一旦进表，它的 setHealth 会被永久劫持到 Native。</p>
 */
public final class FieldIsolation {

    private static final Set<UUID> ISOLATED = ConcurrentHashMap.newKeySet();

    private FieldIsolation() {
    }

    /** 唯一允许的查询入口：必须传实体对象（带 instanceof Player 判定）。 */
    public static boolean isIsolated(Object entity) {
        if (!(entity instanceof Player)) {
            return false;
        }
        return ISOLATED.contains(((Player) entity).getUUID());
    }

    public static boolean isIsolatedByUuid(UUID id) {
        return ISOLATED.contains(id);
    }

    public static void isolate(UUID id) {
        ISOLATED.add(id);
    }

    public static void release(UUID id) {
        ISOLATED.remove(id);
    }

    public static void releaseAll() {
        ISOLATED.clear();
    }

    public static int size() {
        return ISOLATED.size();
    }
}
