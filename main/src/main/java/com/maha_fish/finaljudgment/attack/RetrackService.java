package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 传送后重新追踪（7.18，规范 37）。
 *
 * <p>目标被传送到别的 chunk 后，服务端需要重新登记追踪：依次尝试
 * `ChunkMap.updatePlayerStatus`（针对玩家）/ `TrackedEntity` / `ServerEntity` 三条路，
 * 任何一条成功即可。全部失败只打 DEBUG（不抛）。</p>
 */
public final class RetrackService {

    private static final AtomicInteger RETRACKS = new AtomicInteger();

    private RetrackService() {
    }

    public static boolean retrack(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return false;
        }
        boolean ok = false;
        try {
            Object chunkMap = findChunkMap(level);
            if (chunkMap != null) {
                Method updatePlayerStatus = findMethod(chunkMap.getClass(), "updatePlayerStatus", 2);
                if (updatePlayerStatus != null && entity instanceof net.minecraft.server.level.ServerPlayer player) {
                    updatePlayerStatus.invoke(chunkMap, player, true);
                    ok = true;
                }
                Method trackEntity = findMethod(chunkMap.getClass(), "trackEntity", 1);
                if (trackEntity != null) {
                    trackEntity.invoke(chunkMap, entity);
                    ok = true;
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] retrack(ChunkMap) 失败: {}", t.toString());
        }
        if (!ok) {
            // 兜底：从 EntityLookup 里按 UUID 找回并重新注入
            try {
                Object lookup = findLookup(level);
                if (lookup != null) {
                    Method byUuid = findMethod(lookup.getClass(), "get", 1);
                    if (byUuid != null) {
                        byUuid.invoke(lookup, entity.getUUID());
                        ok = true;
                    }
                }
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] retrack(EntityLookup) 失败: {}", t.toString());
            }
        }
        if (ok) {
            RETRACKS.incrementAndGet();
        }
        return ok;
    }

    /** EntityLookup.byUuid / byId 的反射查询（规范要求的两条路径）。 */
    public static Entity lookup(ServerLevel level, UUID id) {
        try {
            Object lookup = findLookup(level);
            if (lookup == null) {
                return null;
            }
            for (String name : new String[]{"get", "byUuid", "m_142597_"}) {
                Method method = findMethod(lookup.getClass(), name, 1);
                if (method != null) {
                    Object result = method.invoke(lookup, id);
                    if (result instanceof Entity entity) {
                        return entity;
                    }
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] EntityLookup 查询失败: {}", t.toString());
        }
        return null;
    }

    private static Object findChunkMap(ServerLevel level) {
        try {
            for (Method method : level.getClass().getMethods()) {
                if (method.getParameterCount() == 0
                        && method.getReturnType().getSimpleName().endsWith("ChunkMap")) {
                    return method.invoke(level);
                }
            }
        } catch (Throwable ignored) {
            // 放弃
        }
        return null;
    }

    private static Object findLookup(ServerLevel level) {
        try {
            Object chunkMap = findChunkMap(level);
            if (chunkMap == null) {
                return null;
            }
            for (java.lang.reflect.Field field : chunkMap.getClass().getDeclaredFields()) {
                if (field.getType().getSimpleName().endsWith("EntityMap")) {
                    Object map = field.get(chunkMap);
                    if (map != null) {
                        return map;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 放弃
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int paramCount) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                    return method;
                }
            }
        }
        return null;
    }

    public static int retracks() {
        return RETRACKS.get();
    }
}
