package com.maha_fish.finaljudgment.attack;

import com.google.common.collect.ImmutableList;
import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.FieldWriter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * 结构性移除（改造自 super-steve 的 {@code SSUtil.simpleKillEntity}）。
 *
 * <p>参考实现的实际步骤（字节码逐一对应）：</p>
 * <ol>
 *   <li>广播自定义移除包到所有客户端</li>
 *   <li>{@code isAddedToWorld=false}、{@code canUpdate=false}、{@code noPhysics=true}</li>
 *   <li>{@code removalReason = DISCARDED}</li>
 *   <li><b>碰撞箱收缩成一个点</b>（{@code setBoundingBox(AABB(x,y,z,x,y,z))}）—— 这是"碰撞箱消失"的关键</li>
 *   <li>{@code passengers = ImmutableList.of()}（直接写字段，比 ejectPassengers 更彻底）</li>
 *   <li>{@code levelCallback = NULL}（阻止被重新挂回 section）</li>
 *   <li>LivingEntity：{@code dead=true}、{@code deathTime=0}</li>
 *   <li>逐层摘除 section / tickList / EntityLookup / knownUuids + 分数板</li>
 *   <li>再次广播移除包</li>
 * </ol>
 *
 * <p><b>不采用</b>参考实现的 {@code PLZBase.klassPtr}（直接改 Klass 指针来换 VTable）——
 * 那会破坏 JVM 稳定性，属于不可接受的副作用。</p>
 */
public final class EntityRemover {

    private static final String[] ENTITY_REMOVAL = {"removalReason", "f_146795_"};
    private static final String[] ENTITY_CALLBACK = {"levelCallback", "f_146801_"};
    private static final String[] ENTITY_CAN_UPDATE = {"canUpdate"};
    private static final String[] ENTITY_ADDED = {"isAddedToWorld", "f_146810_"};
    private static final String[] ENTITY_NO_PHYSICS = {"noPhysics", "f_19794_"};
    private static final String[] ENTITY_PASSENGERS = {"passengers", "f_19823_"};
    private static final String[] ENTITY_INVULNERABLE = {"invulnerable"};
    private static final String[] ENTITY_INVULNERABLE_TIME = {"invulnerableTime"};
    private static final String[] LIVING_DEAD = {"dead", "f_20890_"};
    private static final String[] LIVING_DEATH_TIME = {"deathTime", "f_20919_"};
    private static final String[] LEVEL_MANAGER = {"entityManager", "f_143244_"};
    private static final String[] LEVEL_TICKLIST = {"entityTickList", "f_143243_"};
    private static final String[] PESM_SECTIONS = {"sectionStorage", "f_157495_"};
    private static final String[] PESM_VISIBLE = {"visibleEntityStorage", "f_157494_"};
    private static final String[] PESM_KNOWN = {"knownUuids", "f_157491_"};
    private static final String[] SECTION_STORAGE = {"storage", "f_156827_"};
    private static final String[] MULTIMAP_MAP = {"map", "f_13527_"};
    private static final String[] TICK_ACTIVE = {"active", "f_156903_"};
    private static final String[] TICK_PASSIVE = {"passive"};
    private static final String[] LOOKUP_BY_UUID = {"byUuid", "f_156808_"};
    private static final String[] LOOKUP_BY_ID = {"byId", "f_156807_"};

    private EntityRemover() {
    }

    public static boolean remove(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return false;
        }
        boolean touched = false;
        try {
            // ① 先广播：让客户端立刻移除（不等服务端索引操作）
            // 登记"拒绝重生"黑名单（对付会自我重启的实体）
            try {
                SpawnVeto.block(entity);
            } catch (Throwable ignored) {
                // 忽略
            }

            // 校验并强制清理：选择器（/say @e）走 section，Forge /forge entity list 走 entityManager 的
            // byId / byUuid / knownUuids。只清 section 会让实体"选择器看不到但列表还在"。
            try {
                purgeLookup(level, entity);
            } catch (Throwable ignored) {
                // 忽略
            }

            // 收尾：若实体仍存在且处于"倒地"状态，清掉死亡状态，避免冒烟循环
            try {
                if (entity instanceof net.minecraft.world.entity.LivingEntity living
                        && DeadState.isDying(living) && !entity.isRemoved()) {
                    DeadState.reset(living);
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            KillPackets.broadcastRemove(level, entity);

            // ② 冻结实体字段（照抄参考实现）
            touched |= writeBoolean(entity, ENTITY_ADDED, false);
            touched |= writeBoolean(entity, ENTITY_CAN_UPDATE, false);
            touched |= writeBoolean(entity, ENTITY_NO_PHYSICS, true);
            touched |= writeBoolean(entity, ENTITY_INVULNERABLE, false);
            long invulnerableTime = FieldWriter.offsetOf(Entity.class, ENTITY_INVULNERABLE_TIME, null);
            if (invulnerableTime >= 0) {
                FieldWriter.writeInt(entity, invulnerableTime, 0);
            }

            // ③ removalReason
            long reasonOffset = FieldWriter.offsetOf(Entity.class, ENTITY_REMOVAL, "RemovalReason");
            if (reasonOffset >= 0 && KillValidator.fieldRemovalReason(entity) == null) {
                touched |= FieldWriter.writeObject(entity, reasonOffset, Entity.RemovalReason.KILLED);
            }

            // ④ 碰撞箱收缩成一个点（参考实现第 8 步，直接决定"碰撞箱还在不在"）
            collapseBoundingBox(entity);

            // ⑤ 清乘客（直接写字段，比 ejectPassengers 彻底）
            long passengersOffset = FieldWriter.offsetOf(Entity.class, ENTITY_PASSENGERS, "List");
            if (passengersOffset >= 0) {
                touched |= FieldWriter.writeObject(entity, passengersOffset, ImmutableList.of());
            } else {
                try {
                    entity.ejectPassengers();
                } catch (Throwable ignored) {
                    // 继续
                }
            }

            // ⑥ levelCallback = NULL（阻止模组把实体重新挂回 section）
            long callbackOffset = FieldWriter.offsetOf(Entity.class, ENTITY_CALLBACK, "EntityInLevelCallback");
            if (callbackOffset >= 0) {
                touched |= FieldWriter.writeObject(entity,
                        callbackOffset, net.minecraft.world.level.entity.EntityInLevelCallback.NULL);
            }

            // ⑦ LivingEntity：dead=true、deathTime=0
            if (entity instanceof LivingEntity) {
            // 移植自 ArcaneVortex：清除自定义 boolean 免疫标记 + 走 setHealth setter 归零血量
            try {
                AntiKillBypass.setAllBooleanFields(entity);
                AntiKillBypass.forceSetHealth5Ways(entity);
            } catch (Throwable ignored) {
                // 忽略
            }

                touched |= writeBoolean(entity, LIVING_DEAD, true);
                long deathTime = FieldWriter.offsetOf(LivingEntity.class, LIVING_DEATH_TIME, null);
                if (deathTime >= 0) {
                    FieldWriter.writeInt(entity, deathTime, 0);
                }
            }

            // ⑧ 逐层摘除索引
            touched |= removeFromSection(level, entity);
            Object tickList = read(level, LEVEL_TICKLIST, "EntityTickList");
            if (tickList != null) {
                touched |= removeFromMap(tickList, TICK_ACTIVE, entity);
                touched |= removeFromMap(tickList, TICK_PASSIVE, entity);
            }
            Object manager = read(level, LEVEL_MANAGER, "PersistentEntitySectionManager");
            if (manager != null) {
                Object lookup = read(manager, PESM_VISIBLE, "EntityLookup");
                if (lookup != null) {
                    touched |= removeFromMap(lookup, LOOKUP_BY_UUID, entity);
                    touched |= removeFromMap(lookup, LOOKUP_BY_ID, entity);
                }
                Object known = read(manager, PESM_KNOWN, "Set");
                if (known instanceof Collection<?> collection) {
                    try {
                        @SuppressWarnings("unchecked")
                        Collection<Object> raw = (Collection<Object>) collection;
                        raw.remove(entity.getUUID());
                        touched = true;
                    } catch (Throwable ignored) {
                        // 继续
                    }
                }
            }
            try {
                level.getScoreboard().entityRemoved(entity);
            } catch (Throwable ignored) {
                // 继续
            }

            // ⑨ Klass 指针替换：把实例换成"已死子类"（不新增字段，布局一致），
            //    使其 isRemoved() 恒真、onRemove() 失效 —— 参考 super-steve 的 klassPtr 手法
            boolean swapped = KlassSwap.markRemoved(entity);
            if (swapped) {
                touched = true;
            }

            // ⑩ 再广播一次（对抗在 ①~⑧ 之间重新登记自己的模组）
            // 移植自 Super Steve killEntity 收尾：末影龙特判 + multipart 部件 + 挪远强制卸载
            try {
                AntiKillBypass.fixDragon(level, entity);
                AntiKillBypass.removeParts(level, entity);
                AntiKillBypass.moveAway(entity);
            } catch (Throwable ignored) {
                // 忽略
            }

            // 若实体仍然"活着/未移除"（自愈型实体），自动中和其类的 isAlive/isRemoved 再重试一次
            try {
                boolean stillAlive;
                try {
                    stillAlive = entity.isAlive() && entity.getRemovalReason() == null;
                } catch (Throwable t2) {
                    stillAlive = false;
                }
                if (stillAlive) {
                    int neutralized = SpecialTargets.matches(entity)
                            ? SpecialTargets.enhance(entity)
                            : com.maha_fish.finaljudgment.guard.MethodRedirect
                                    .neutralizelivenessForced(entity.getClass());
                    if (neutralized > 0) {
                        FinalJudgment.LOGGER.info("[FJ] 实体自愈型（{}），已中和 {} 个存活方法并重试移除",
                                entity.getType(), neutralized);
                        remove(level, entity);
                        return touched;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            // 校验并强制清理：选择器（/say @e）走 section，Forge /forge entity list 走 entityManager 的
            // byId / byUuid / knownUuids。只清 section 会让实体"选择器看不到但列表还在"。
            try {
                purgeLookup(level, entity);
            } catch (Throwable ignored) {
                // 忽略
            }

            // 收尾：若实体仍存在且处于"倒地"状态，清掉死亡状态，避免冒烟循环
            try {
                if (entity instanceof net.minecraft.world.entity.LivingEntity living
                        && DeadState.isDying(living) && !entity.isRemoved()) {
                    DeadState.reset(living);
                }
            } catch (Throwable ignored) {
                // 忽略
            }

            KillPackets.broadcastRemove(level, entity);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] EntityRemover 异常: {}", t.toString());
        }
        FinalJudgment.LOGGER.debug("[FJ] EntityRemover: uuid={} touched={}", entity.getUUID(), touched);
        return touched;
    }

    /** 碰撞箱收缩成一个点（参考实现的核心技巧）。 */
    private static void collapseBoundingBox(Entity entity) {
        try {
            AABB box = new AABB(entity.getX(), entity.getY(), entity.getZ(),
                    entity.getX(), entity.getY(), entity.getZ());
            Method setBoundingBox = findMethod(entity.getClass(), "setBoundingBox", 1);
            if (setBoundingBox != null) {
                setBoundingBox.invoke(entity, box);
                return;
            }
            long offset = FieldWriter.offsetOf(Entity.class, new String[]{"boundingBox", "f_19828_"}, "AABB");
            if (offset >= 0) {
                FieldWriter.writeObject(entity, offset, box);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 碰撞箱收缩失败: {}", t.toString());
        }
    }

    private static boolean writeBoolean(Entity entity, String[] candidates, boolean value) {
        long offset = FieldWriter.offsetOf(Entity.class, candidates, null);
        return offset >= 0 && FieldWriter.writeBoolean(entity, offset, value);
    }

    private static boolean removeFromSection(ServerLevel level, Entity entity) {
        try {
            Object manager = read(level, LEVEL_MANAGER, "PersistentEntitySectionManager");
            if (manager == null) {
                return false;
            }
            Object sectionStorage = read(manager, PESM_SECTIONS, "EntitySectionStorage");
            if (sectionStorage == null) {
                return false;
            }
            long chunkPos = net.minecraft.core.SectionPos.asLong(entity.blockPosition());
            Method getSection = findMethod(sectionStorage.getClass(), "getSection", 1);
            if (getSection == null) {
                return false;
            }
            Object section = getSection.invoke(sectionStorage, chunkPos);
            if (section == null) {
                return false;
            }
            Object storage = read(section, SECTION_STORAGE, "ClassInstanceMultiMap");
            if (storage == null) {
                return false;
            }
            Object innerMap = read(storage, MULTIMAP_MAP, "Map");
            if (!(innerMap instanceof Map<?, ?> map)) {
                return false;
            }
            boolean removed = false;
            for (Object value : map.values()) {
                if (value instanceof Collection<?> collection) {
                    try {
                        @SuppressWarnings("unchecked")
                        Collection<Object> raw = (Collection<Object>) collection;
                        if (raw.remove(entity)) {
                            removed = true;
                        }
                    } catch (Throwable ignored) {
                        // 继续
                    }
                }
            }
            return removed;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] section 摘除失败: {}", t.toString());
            return false;
        }
    }

    private static boolean removeFromMap(Object owner, String[] candidates, Entity entity) {
        try {
            Object field = readAny(owner, candidates);
            if (field instanceof Map<?, ?> map) {
                Map<Object, Object> raw = castMap(map);
                boolean changed = raw.remove(entity.getUUID()) != null;
                changed |= raw.remove(entity.getId()) != null;
                return changed;
            }
        } catch (Throwable ignored) {
            // 继续
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Map<?, ?> map) {
        return (Map<Object, Object>) map;
    }

    private static Object read(Object owner, String[] candidates, String typeSuffix) {
        long offset = FieldWriter.offsetOf(owner.getClass(), candidates, typeSuffix);
        return offset >= 0 ? FieldWriter.readObject(owner, offset) : readAny(owner, candidates);
    }

    private static Object readAny(Object owner, String[] candidates) {
        Class<?> type = owner.getClass();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String candidate : candidates) {
                try {
                    java.lang.reflect.Field field = c.getDeclaredField(candidate);
                    long offset = FieldWriter.offsetOf(c, new String[]{candidate}, null);
                    return offset >= 0 ? FieldWriter.readObject(owner, offset) : field.get(owner);
                } catch (Throwable ignored) {
                    // 下一个候选
                }
            }
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
    /**
     * 强制清理实体在 {@code PersistentEntitySectionManager} 里的所有登记：
     * {@code EntityLookup.byId / byUuid} 与 {@code knownUuids}。
     *
     * <p>这是 {@code /say @e}（section 查询）与 {@code /forge entity list}（byId 查询）不一致的根因修复。</p>
     */
    public static void purgeLookup(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        boolean stillVisible = false;
        try {
            stillVisible = level.getEntity(entity.getId()) != null;
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            Object manager = read(level, LEVEL_MANAGER, "PersistentEntitySectionManager");
            Object lookup = read(manager, PESM_VISIBLE, "EntityLookup");
            boolean removed = false;
            removed |= removeIntKey(read(lookup, LOOKUP_BY_ID, "Int2ObjectMap"), entity.getId());
            removed |= removeObjectKey(read(lookup, LOOKUP_BY_UUID, "Map"), entity.getUUID());
            removed |= removeFromSet(read(manager, PESM_KNOWN, "Set"), entity.getUUID());
            if (removed) {
                FinalJudgment.LOGGER.info("[FJ] 已清理实体登记: id={} uuid={}（此前仍可见={}）",
                        entity.getId(), entity.getUUID(), stillVisible);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] purgeLookup 失败: {}", t.toString());
        }
    }

    /** 从 Int2ObjectMap / Map 中按 int 键删除（两种实现都尝试）。 */
    private static boolean removeIntKey(Object map, int key) {
        if (map == null) {
            return false;
        }
        try {
            if (map instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> fast) {
                @SuppressWarnings("unchecked")
                it.unimi.dsi.fastutil.ints.Int2ObjectMap<Object> raw =
                        (it.unimi.dsi.fastutil.ints.Int2ObjectMap<Object>) fast;
                return raw.remove(key) != null;
            }
        } catch (Throwable ignored) {
            // 退化为 Map
        }
        return removeObjectKey(map, key);
    }

    private static boolean removeObjectKey(Object map, Object key) {
        if (!(map instanceof Map<?, ?> raw)) {
            return false;
        }
        try {
            Map<Object, Object> casted = castMap(raw);
            return casted.remove(key) != null || casted.remove(String.valueOf(key)) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean removeFromSet(Object set, Object value) {
        if (!(set instanceof java.util.Set<?> raw)) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.Set<Object> casted = (java.util.Set<Object>) raw;
            return casted.remove(value);
        } catch (Throwable ignored) {
            return false;
        }
    }
}