package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 深度扫容器（7.4.4 功能 2）：从对象图任意位置移除对目标的引用。
 *
 * <p>限深 5、IdentityHashMap 去重、跳过 JDK 核心类、遍历用快照（避免并发修改）。
 * 只在 KillChain 触发时执行（规范 51）。</p>
 */
public final class MapSweep {

    private static final int MAX_DEPTH = 5;

    private MapSweep() {
    }

    /**
     * @return 实际移除的引用数（0 表示没找到，<b>不是失败</b>）
     */
    public static int sweep(Object root, Object target, UUID id) {
        if (root == null || target == null) {
            return 0;
        }
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        int[] removed = new int[1];
        try {
            walk(root, target, id, 0, seen, removed);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] MapSweep 中断: {}", t.toString());
        }
        return removed[0];
    }

    private static void walk(Object node, Object target, UUID id, int depth,
                             IdentityHashMap<Object, Boolean> seen, int[] removed) {
        if (node == null || depth > MAX_DEPTH || seen.put(node, Boolean.TRUE) != null) {
            return;
        }
        Class<?> type = node.getClass();
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("it.unimi.")) {
            // JDK 容器本身要处理（Map/Collection），其元素不再递归
            sweepContainer(node, target, id, removed);
            return;
        }
        if (node instanceof Map || node instanceof Collection) {
            sweepContainer(node, target, id, removed);
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    Object child = field.get(node);
                    if (child != null) {
                        walk(child, target, id, depth + 1, seen, removed);
                    }
                } catch (Throwable ignored) {
                    // 该字段不可读，跳过
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void sweepContainer(Object node, Object target, UUID id, int[] removed) {
        try {
            if (node instanceof Map) {
                Map map = (Map) node;
                for (Object key : new ArrayList<>(map.keySet())) {
                    if (matches(map.get(key), key, target, id)) {
                        map.remove(key);
                        removed[0]++;
                    }
                }
            } else if (node instanceof Collection) {
                Collection collection = (Collection) node;
                for (Object element : new ArrayList<>(collection)) {
                    if (matches(element, null, target, id)) {
                        collection.remove(element);
                        removed[0]++;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 并发修改等，放过
        }
    }

    private static boolean matches(Object value, Object key, Object target, UUID id) {
        if (value == target) {
            return true;
        }
        if (id == null) {
            return false;
        }
        return id.equals(value) || id.equals(key);
    }
}
