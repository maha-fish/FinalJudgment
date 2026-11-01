package com.maha_fish.finaljudgment.coremod;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tick 级击杀检查（5.2 功能 5、6）。
 *
 * <p>由 {@link Patches} 在 Entity.tick 入口注入调用。<b>绝不对 tick 抛异常</b>：
 * 整个方法体被 try/catch 包住。</p>
 *
 * <p>跨 CL：用<b>实体对象的 ClassLoader</b> 加载主模块的 {@code KillRegistry}（同一 CL），
 * 反射结果缓存，避免每 tick 查找。参数一律 {@code Object}（coremod 编译期不依赖 MC 类）。</p>
 */
public final class CoremodKillTicker {

    private static final String KILL_REGISTRY = "com.maha_fish.finaljudgment.attack.KillRegistry";

    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    private static volatile boolean clientTickLogged;

    private CoremodKillTicker() {
    }

    /** Entity.tick 入口（服务端与客户端都跑，但内部只查标记）。 */
    public static void tick(Object entity) {
        try {
            if (entity == null) {
                return;
            }
            ClassLoader loader = entity.getClass().getClassLoader();
            Method isMarked = method(loader, "isMarked", new Class<?>[]{Object.class});
            if (isMarked == null || !Boolean.TRUE.equals(isMarked.invoke(null, entity))) {
                return;
            }
            Method forceKill = method(loader, "forceKill", new Class<?>[]{Object.class});
            if (forceKill != null) {
                forceKill.invoke(null, entity);
            }
        } catch (Throwable ignored) {
            // 绝不对 tick 抛异常
        }
    }

    /** Minecraft.runTick 入口（仅客户端）。 */
    public static void clientTick() {
        if (clientTickLogged) {
            return;
        }
        clientTickLogged = true;
        CoremodLog.info("客户端 tick hook 已生效（Minecraft.runTick 注入成功）");
    }

    private static Method method(ClassLoader loader, String name, Class<?>[] parameterTypes) {
        String key = name + ":" + loader;
        Object cached = CACHE.get(key);
        if (cached instanceof Method) {
            return (Method) cached;
        }
        if (cached != null) {
            return null;
        }
        try {
            Class<?> registry = Class.forName(KILL_REGISTRY, true, loader);
            Method method = registry.getMethod(name, parameterTypes);
            CACHE.put(key, method);
            return method;
        } catch (Throwable t) {
            CACHE.put(key, Boolean.FALSE);
            return null;
        }
    }
}
