package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;

/**
 * 实体重新注入（7.4.4 功能 3）：把实体放回服务端索引。
 *
 * <p>反射调用 {@code entityManager.addEntity}（参数类型用 Object，泛型擦除），
 * 失败降级 {@code level.addFreshEntity}。返回值 false 表示两条路都失败。</p>
 */
public final class EntityReinjector {

    private EntityReinjector() {
    }

    public static boolean reattach(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return false;
        }
        try {
            Object manager = readManager(level);
            if (manager != null) {
                for (Method method : manager.getClass().getMethods()) {
                    if (!method.getName().equals("addEntity")) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 1) {
                        continue;
                    }
                    method.invoke(manager, entity);
                    FinalJudgment.LOGGER.debug("[FJ] 实体已重新注入 entityManager");
                    return true;
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] entityManager.addEntity 失败: {}", t.toString());
        }
        try {
            return level.addFreshEntity(entity);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] level.addFreshEntity 失败: {}", t.toString());
            return false;
        }
    }

    private static Object readManager(ServerLevel level) {
        for (Method method : level.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                    && method.getReturnType().getSimpleName().endsWith("PersistentEntitySectionManager")) {
                try {
                    return method.invoke(level);
                } catch (Throwable ignored) {
                    // 换下一个
                }
            }
        }
        try {
            for (java.lang.reflect.Field field : level.getClass().getDeclaredFields()) {
                if (field.getType().getSimpleName().endsWith("PersistentEntitySectionManager")) {
                    field.setAccessible(false);
                    return field.get(level);
                }
            }
        } catch (Throwable ignored) {
            // 放弃
        }
        return null;
    }
}
