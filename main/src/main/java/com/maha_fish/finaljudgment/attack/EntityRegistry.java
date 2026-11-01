package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实体登记表（联调修复）。
 *
 * <p><b>为什么必需</b>：某些实体（含本模组自己击杀过的、以及装了"实体移除"类模组的）
 * 会把自己从 {@code EntityLookup} / {@code ChunkMap} 里摘掉，
 * 于是 {@code level.getAllEntities()} 再也看不到它 —— 目标选取与死亡判定同时失灵
 * （参考 omnimobs 的 EntityRemovalUtil：它同样绕开列表、直接对对象做结构性移除）。</p>
 *
 * <p>做法：监听 {@link EntityJoinLevelEvent} 记录所有进入过世界的实体（弱引用，随 GC 自动清理），
 * 选取时把登记表与 {@code getAllEntities()} 合并去重。</p>
 */
public final class EntityRegistry {

    private static final Map<UUID, WeakReference<Entity>> KNOWN = new ConcurrentHashMap<>();
    private static final AtomicInteger REGISTERED = new AtomicInteger();

    public EntityRegistry() {
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() != null) {
            // 重生拦截：被击杀过的 UUID 一律拒绝重新加入世界（对付"会重启"的实体）
            if (SpawnVeto.shouldVeto(event.getEntity())) {
                event.setCanceled(true);
                return;
            }
            remember(event.getEntity());
        }
    }

    /** 每 20 tick 清理一次"已进黑名单但仍混在世界里"的实体。 */
    @SubscribeEvent
    public void onLevelTick(net.minecraftforge.event.TickEvent.LevelTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (!(event.level instanceof ServerLevel serverLevel)) {
            return;
        }
        if ((serverLevel.getGameTime() & 19L) != 0L) {
            return;
        }
        try {
            SpawnVeto.sweep(serverLevel);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 清扫异常: {}", t.toString());
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        KNOWN.entrySet().removeIf(entry -> {
            Entity entity = entry.getValue() == null ? null : entry.getValue().get();
            return entity == null || entity.level() == event.getLevel();
        });
    }

    public static void remember(Entity entity) {
        if (entity == null || entity instanceof Player) {
            return;
        }
        if (KNOWN.put(entity.getUUID(), new WeakReference<>(entity)) == null) {
            REGISTERED.incrementAndGet();
        }
    }

    /** 按 UUID 取（即便它已从所有服务端索引里消失）。 */
    public static Entity find(UUID id) {
        if (id == null) {
            return null;
        }
        WeakReference<Entity> reference = KNOWN.get(id);
        Entity entity = reference == null ? null : reference.get();
        if (entity == null && reference != null) {
            KNOWN.remove(id);
        }
        return entity;
    }

    /**
     * 合并候选：登记表 + getAllEntities()（去重）。
     * 隐藏实体只可能来自登记表。
     */
    /** 反射读字段（MCP 名与 SRG 名都试）；typeSuffix 仅用于日志语义，可为 null。 */
    private static Object read(Object owner, String[] names, String typeSuffix) {
        if (owner == null || names == null) {
            return null;
        }
        for (String name : names) {
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (Throwable ignored) {
                    // 下一个候选
                }
            }
        }
        return null;
    }

    public static List<Entity> candidates(ServerLevel level) {
        List<Entity> merged = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        try {
            for (Entity entity : level.getAllEntities()) {
                if (seen.add(entity.getId())) {
                    merged.add(entity);
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] getAllEntities 失败: {}", t.toString());
        }
        // 原始结构扫描：绕过 getAllEntities()（隐藏实体会从这里消失，但仍在 Lookup 里）
        try {
            Object manager = read(level, new String[]{"entityManager", "f_143244_"}, "PersistentEntitySectionManager");
            Object lookup = read(manager, new String[]{"visibleEntityStorage", "f_157494_"}, "EntityLookup");
            Object byId = read(lookup, new String[]{"byId", "f_156807_"}, "Int2ObjectMap");
            if (byId instanceof java.util.Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value instanceof Entity entity && seen.add(entity.getId())) {
                        merged.add(entity);
                    }
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] Lookup 扫描失败: {}", t.toString());
        }

        Iterator<Map.Entry<UUID, WeakReference<Entity>>> iterator = KNOWN.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, WeakReference<Entity>> entry = iterator.next();
            Entity entity = entry.getValue() == null ? null : entry.getValue().get();
            if (entity == null || entity.isRemoved()) {
                iterator.remove();
                continue;
            }
            if (entity.level() != level) {
                continue;
            }
            if (seen.add(entity.getId())) {
                merged.add(entity);
            }
        }
        return merged;
    }

    /** 被隐藏（已不在任何服务端索引）但仍存活。 */
    public static boolean isHiddenButAlive(ServerLevel level, Entity entity) {
        if (level == null || entity == null || entity.isRemoved()) {
            return false;
        }
        try {
            return level.getEntity(entity.getId()) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int size() {
        return KNOWN.size();
    }

    public static int registered() {
        return REGISTERED.get();
    }

    /** 距离辅助（统一用 position()，不用 boundingBox）。 */
    public static double distance(Player player, Entity entity) {
        Vec3 a = player.position();
        Vec3 b = entity.position();
        return a.distanceTo(b);
    }

    public static boolean isLivingTarget(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive();
    }
}
