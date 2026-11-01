package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;
import com.maha_fish.finaljudgment.common.SRGNames;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 6 处集合摘除（7.4.4 功能 1）。
 *
 * <p>全部走反射 + 描述符匹配（字段名候选见 {@link SRGNames} 的 7b 段），
 * <b>纯 Object 操作，不硬编码 MC 泛型类型</b>，避免因 API 差异编译不过。</p>
 *
 * <p>返回成功摘除的处数：<b>0 不是失败</b>（实体可能本来就不在这些集合里）。</p>
 */
public final class StorageExtract {

    private StorageExtract() {
    }

    public static int extract(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return 0;
        }
        int removed = 0;
        UUID id = entity.getUUID();
        int entityId = entity.getId();

        try {
            Object manager = readField(level, SRGNames.SERVER_LEVEL_ENTITY_MANAGER, "PersistentEntitySectionManager");
            if (manager != null) {
                // ① knownUuids
                removed += removeFrom(manager, SRGNames.PERSISTENT_KNOWN_UUIDS, "Set", id, entity);
                // ② visibleEntityStorage
                removed += removeFrom(manager, SRGNames.PERSISTENT_VISIBLE_STORAGE, "EntityLookup", id, entity);
                // ③ sectionStorage → sections → EntitySection.storage
                Object sectionStorage = readField(manager, SRGNames.PERSISTENT_SECTION_STORAGE, "EntitySectionStorage");
                if (sectionStorage != null) {
                    Object sections = readField(sectionStorage, SRGNames.ENTITY_SECTION_STORAGE_SECTIONS, "Long2ObjectMap");
                    removed += sweepSections(sections, entity, id);
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 摘除(entityManager 侧) 出错: {}", t.toString());
        }

        // ④⑤ EntityTickList.active / passive
        try {
            Object tickList = readField(level, SRGNames.SERVER_LEVEL_ENTITY_TICK_LIST, "EntityTickList");
            if (tickList != null) {
                removed += removeFromMap(tickList, SRGNames.ENTITY_TICK_LIST_ACTIVE, "Int2ObjectMap", entityId, entity, id);
                removed += removeFromMap(tickList, SRGNames.ENTITY_TICK_LIST_PASSIVE, "Int2ObjectMap", entityId, entity, id);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 摘除(tickList) 出错: {}", t.toString());
        }

        // ⑥ ChunkMap.entityMap
        try {
            Object chunkMap = readField(level, SRGNames.SERVER_CHUNK_CACHE_CHUNK_MAP, "ChunkMap");
            if (chunkMap == null) {
                Object chunkSource = readField(level, new String[]{"chunkSource", "f_7722_"}, "ServerChunkCache");
                chunkMap = chunkSource == null ? null
                        : readField(chunkSource, SRGNames.SERVER_CHUNK_CACHE_CHUNK_MAP, "ChunkMap");
            }
            if (chunkMap != null) {
                removed += removeFromMap(chunkMap, SRGNames.CHUNK_MAP_ENTITY_MAP, "Int2ObjectMap", entityId, entity, id);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 摘除(chunkMap) 出错: {}", t.toString());
        }

        if (removed == 0) {
            FinalJudgment.LOGGER.debug("[FJ] 集合摘除: 0 处（不算失败）entity={}", id);
        }
        return removed;
    }

    // ===================== 工具 =====================

    private static Object readField(Object owner, String[] candidates, String typeSuffix) {
        if (owner == null) {
            return null;
        }
        Class<?> type = owner.getClass();
        Field field = null;
        for (Class<?> c = type; c != null && c != Object.class && field == null; c = c.getSuperclass()) {
            for (String candidate : candidates) {
                try {
                    field = c.getDeclaredField(candidate);
                    break;
                } catch (Throwable ignored) {
                    // 下一个候选
                }
            }
            if (field == null && typeSuffix != null) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getSimpleName().endsWith(typeSuffix)) {
                        field = f;
                        break;
                    }
                }
            }
        }
        if (field == null) {
            return null;
        }
        long offset = FieldWriter.offsetOf(type, candidates, typeSuffix);
        if (offset >= 0) {
            return FieldWriter.readObject(owner, offset);
        }
        try {
            return field.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int removeFrom(Object owner, String[] candidates, String typeSuffix, UUID id, Object entity) {
        Object container = readField(owner, candidates, typeSuffix);
        return removeFromContainer(container, entity, id, -1);
    }

    private static int removeFromMap(Object owner, String[] candidates, String typeSuffix,
                                     int key, Object entity, UUID id) {
        Object container = readField(owner, candidates, typeSuffix);
        return removeFromContainer(container, entity, id, key);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int removeFromContainer(Object container, Object entity, UUID id, int key) {
        if (container == null) {
            return 0;
        }
        int removed = 0;
        try {
            if (container instanceof Map) {
                Map map = (Map) container;
                if (key >= 0 && map.containsKey(key)) {
                    map.remove(key);
                    removed++;
                }
                if (map.containsKey(id)) {
                    map.remove(id);
                    removed++;
                }
                for (Object candidateKey : new ArrayList<>(map.keySet())) {
                    if (map.get(candidateKey) == entity) {
                        map.remove(candidateKey);
                        removed++;
                    }
                }
            } else if (container instanceof Collection) {
                Collection collection = (Collection) container;
                for (Object element : new ArrayList<>(collection)) {
                    if (element == entity || id.equals(element)) {
                        collection.remove(element);
                        removed++;
                    }
                }
            } else {
                // EntityLookup 之类的自定义容器：退化为深度扫
                removed += MapSweep.sweep(container, entity, id);
            }
        } catch (Throwable ignored) {
            // 并发修改等
        }
        return removed;
    }

    private static int sweepSections(Object sections, Object entity, UUID id) {
        if (!(sections instanceof Map)) {
            return MapSweep.sweep(sections, entity, id);
        }
        int removed = 0;
        try {
            for (Object section : new ArrayList<>(((Map<?, ?>) sections).values())) {
                Object storage = readField(section, SRGNames.ENTITY_SECTION_STORAGE, "ClassInstanceMultiMap");
                removed += removeFromContainer(storage, entity, id, -1);
            }
        } catch (Throwable ignored) {
            // 放过
        }
        return removed;
    }
}
