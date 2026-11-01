package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用防守检测（扩展 4-1）。
 *
 * <p>目标：<b>不硬编码任何 mod 名</b>，靠"继承链 + 覆写特征"识别可疑防御类。</p>
 *
 * <p>特征（满足其一即视为可疑）：</p>
 * <ol>
 *   <li>继承链上出现 {@code Entity / LivingEntity}，且自己声明了
 *       {@code isAlive / isRemoved / isInvisible / hurt / die / remove / setRemoved}
 *       中的 <b>≥2 个</b></li>
 *   <li>类名/接口名/注解名含免疫关键字（交由 {@code SpecialTargets} 判定）</li>
 * </ol>
 *
 * <p>类来源（按可用性降级）：</p>
 * <ul>
 *   <li>① {@code NativeBridge.nativeEnumerateLoadedClasses()}（JVMTI GetLoadedClasses）</li>
 *   <li>② 不可用时跳过扫描（不抛异常）</li>
 * </ul>
 */
public final class DefenseScanner {

    /** 判定"覆写关键方法"的方法名集合（MCP + SRG 双名）。 */
    private static final String[] KEY_METHODS = {
            "isAlive", "m_6084_",
            "isRemoved", "m_213877_",
            "isInvisible", "m_20145_",
            "hurt", "m_6469_",
            "die", "m_6667_",
            "remove", "m_142467_",
            "setRemoved", "m_142467_"
    };

    private static final Map<String, String> SUSPECTS = new LinkedHashMap<>();
    private static final AtomicBoolean SCANNED = new AtomicBoolean(false);
    private static volatile long lastScanMs = 0L;
    private static final long RESCAN_INTERVAL_MS = 30_000L;

    private DefenseScanner() {
    }

    /** 执行一次扫描（幂等）。 */
    public static void scan() {
        if (!SCANNED.compareAndSet(false, true)) {
            return;
        }
        try {
            String[] names = NativeBridge.nativeEnumerateLoadedClasses();
            if (names == null || names.length == 0) {
                FinalJudgment.LOGGER.debug("[FJ] 防守扫描跳过（无法枚举类）");
                return;
            }
            int scanned = 0;
            for (String name : names) {
                Class<?> type = tryLoad(name);
                if (type == null) {
                    continue;
                }
                scanned++;
                String reason = classify(type);
                if (reason != null) {
                    SUSPECTS.put(type.getName(), reason);
                }
            }
            FinalJudgment.LOGGER.info("[FJ] 防守扫描完成: 检查 {} 个类，可疑 {} 个", scanned, SUSPECTS.size());
            for (Map.Entry<String, String> entry : SUSPECTS.entrySet()) {
                FinalJudgment.LOGGER.info("[FJ] 可疑防御类: {}（{}）", entry.getKey(), entry.getValue());
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] 防守扫描异常: {}", t.toString());
        }
    }

    /** 判断一个类是否可疑（也可被外部单类调用）。 */
    public static String classify(Class<?> type) {
        if (type == null) {
            return null;
        }
        try {
            if (type.isArray() || type.isInterface() || type.isPrimitive()) {
                return null;
            }
            if (!isEntitySubclass(type)) {
                return null;
            }
            int overrides = 0;
            List<String> hitNames = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                for (String candidate : KEY_METHODS) {
                    if (method.getName().equals(candidate)) {
                        overrides++;
                        if (!hitNames.contains(candidate)) {
                            hitNames.add(candidate);
                        }
                        break;
                    }
                }
            }
            if (overrides >= 2) {
                return "继承 Entity + 覆写 " + overrides + " 个关键方法 " + hitNames;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    private static boolean isEntitySubclass(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if ("net.minecraft.world.entity.Entity".equals(name)
                    || "net.minecraft.world.entity.LivingEntity".equals(name)
                    || "net.minecraft.world.entity.Mob".equals(name)
                    || "net.minecraft.world.entity.monster.Monster".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 周期重扫（由防御 tick 调用）：首扫立即执行，之后每 30 秒补扫一次
     * —— 启动早期很多类还没加载，只扫一次会漏。
     */
    public static void scanPeriodic() {
        try {
            long now = System.currentTimeMillis();
            if (lastScanMs != 0L && now - lastScanMs < RESCAN_INTERVAL_MS) {
                return;
            }
            lastScanMs = now;
            SCANNED.set(false);
            scan();
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 周期扫描异常: {}", t.toString());
        }
    }

    private static Class<?> tryLoad(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String binary = name.replace('/', '.');
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                DefenseScanner.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(binary, false, loader);
            } catch (Throwable ignored) {
                // 下一个 loader
            }
        }
        return null;
    }

    /** 已发现的可疑类（只读）。 */
    public static Set<String> suspects() {
        return new LinkedHashSet<>(SUSPECTS.keySet());
    }

    public static int suspectCount() {
        return SUSPECTS.size();
    }

    /** 该实体类是否被扫描标记为可疑。 */
    public static boolean isSuspect(Class<?> type) {
        return type != null && SUSPECTS.containsKey(type.getName());
    }
}
