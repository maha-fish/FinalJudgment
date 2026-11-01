package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 字段探测（7b 的一次性诊断）：把 6 处集合摘除相关类的**真实字段名 + 描述符**打进日志，
 * 用于回填 SRGNames 的 MCP/SRG 双候选（不猜名字）。
 */
public final class StorageFieldProbe {

    private static final String[] TARGETS = {
            "net.minecraft.world.level.entity.PersistentEntitySectionManager",
            // 注意：EntityTickList 在 world.level.entity 包下（此前写成 world.entity 是错的）
            "net.minecraft.world.level.entity.EntityTickList",
            "net.minecraft.server.level.ChunkMap",
            "net.minecraft.world.level.entity.EntitySectionStorage",
            "net.minecraft.world.level.entity.EntitySection",
    };

    private StorageFieldProbe() {
    }

    public static void logFields() {
        boolean enabled = !"false".equalsIgnoreCase(System.getProperty("fj.probe.fields", "true"));
        if (!enabled) {
            return;
        }
        for (String name : TARGETS) {
            try {
                Class<?> type = Class.forName(name, false, classLoader());
                FinalJudgment.LOGGER.info("[FJ-PROBE] {} 字段:", name);
                for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        FinalJudgment.LOGGER.info("[FJ-PROBE]   {}{} : {}",
                                Modifier.isStatic(field.getModifiers()) ? "static " : "",
                                field.getName(),
                                field.getType().getName());
                    }
                }
            } catch (Throwable t) {
                FinalJudgment.LOGGER.warn("[FJ-PROBE] {} 探测失败: {}", name, t.toString());
            }
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : StorageFieldProbe.class.getClassLoader();
    }
}
