package com.maha_fish.finaljudgment.defense;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Getter 插桩（扩展 7-10 的支撑设施）。
 *
 * <p>允许对**指定实体**覆盖任意 getter 的返回值，供 Mixin 插桩点查询。
 * 这样新增一个"要欺骗的 getter"只需注册一条覆盖，不必再加 Mixin。</p>
 *
 * <p>已知键：</p>
 * <ul>
 *   <li>{@code isRemoved} → Boolean</li>
 *   <li>{@code removalReason} → Entity.RemovalReason</li>
 *   <li>{@code health} → Float</li>
 *   <li>{@code isAlive} → Boolean</li>
 * </ul>
 */
public final class GetterProbe {

    private static final Map<UUID, Map<String, Object>> OVERRIDES = new ConcurrentHashMap<>();

    private GetterProbe() {
    }

    public static void set(net.minecraft.world.entity.Entity entity, String key, Object value) {
        if (entity == null || key == null) {
            return;
        }
        OVERRIDES.computeIfAbsent(entity.getUUID(), k -> new ConcurrentHashMap<>()).put(key, value);
    }

    public static void clear(net.minecraft.world.entity.Entity entity) {
        if (entity != null) {
            OVERRIDES.remove(entity.getUUID());
        }
    }

    public static boolean has(net.minecraft.world.entity.Entity entity) {
        return entity != null && OVERRIDES.containsKey(entity.getUUID());
    }

    /** 查询覆盖值；外部调用者才生效（我方经 {@link DeceptionContext} 白名单）。 */
    public static Object get(net.minecraft.world.entity.Entity entity, String key) {
        if (entity == null || DeceptionContext.isSelf()) {
            return null;
        }
        Map<String, Object> map = OVERRIDES.get(entity.getUUID());
        return map == null ? null : map.get(key);
    }

    public static Boolean getBoolean(net.minecraft.world.entity.Entity entity, String key) {
        Object value = get(entity, key);
        return value instanceof Boolean b ? b : null;
    }

    public static Float getFloat(net.minecraft.world.entity.Entity entity, String key) {
        Object value = get(entity, key);
        return value instanceof Number n ? n.floatValue() : null;
    }

    public static Object getObject(net.minecraft.world.entity.Entity entity, String key) {
        return get(entity, key);
    }

    public static int size() {
        return OVERRIDES.size();
    }
}