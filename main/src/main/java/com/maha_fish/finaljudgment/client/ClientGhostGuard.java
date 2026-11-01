package com.maha_fish.finaljudgment.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端幽灵黑名单：记录服务端已通知移除的实体 id。
 *
 * <p>服务端广播移除包后，客户端可能因时序仍保留实体对象；
 * 这里把 id 记下来，渲染层据此跳过绘制。</p>
 */
public final class ClientGhostGuard {

    private static final java.util.Map<Integer, Long> REMOVED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000L;

    private ClientGhostGuard() {
    }

    public static void markRemoved(int entityId) {
        REMOVED.put(entityId, System.currentTimeMillis());
    }

    public static void markAlive(int entityId) {
        REMOVED.remove(entityId);
    }

    public static boolean isRemoved(int entityId) {
        return REMOVED.containsKey(entityId);
    }

    /** 周期清理过期 id（TTL 60s），避免集合无限增长。 */
    public static void cleanup() {
        try {
            long now = System.currentTimeMillis();
            REMOVED.entrySet().removeIf(entry -> now - entry.getValue() > TTL_MS);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static int size() {
        return REMOVED.size();
    }
}
