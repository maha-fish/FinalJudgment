package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.attack.KlassSwap;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Klass 指针管理器（对应示例项目的 {@code KlassManager}）。
 *
 * <p>{@link KlassSwap} 只负责"换上去"，本类补两件事：</p>
 * <ol>
 *   <li><b>记录原始类</b>：换之前先记下 {@code instance.getClass()}，便于回退</li>
 *   <li><b>可回退</b>：{@link #restore(Object)} 把对象换回原类（换错时能救回来）</li>
 * </ol>
 *
 * <p>注意：Klass 改写属于高危操作，写错会让 JVM 崩溃；所有入口都带开关与 try/catch。</p>
 */
public final class KlassManager {

    private static final Map<Object, Class<?>> ORIGINAL = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicInteger SWAPS = new AtomicInteger();
    private static final AtomicInteger RESTORES = new AtomicInteger();

    private KlassManager() {
    }

    /** 把对象的 Klass 换成 {@code targetClass}，并记录原始类。 */
    public static boolean swap(Object instance, Class<?> targetClass) {
        if (instance == null || targetClass == null) {
            return false;
        }
        try {
            Class<?> original = instance.getClass();
            ORIGINAL.putIfAbsent(instance, original);
            boolean ok = KlassSwap.writeKlass(instance, targetClass);
            if (ok) {
                SWAPS.incrementAndGet();
                FinalJudgment.LOGGER.debug("[FJ] Klass 已替换: {} → {}",
                        original.getSimpleName(), targetClass.getSimpleName());
            }
            return ok;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] Klass 替换失败: {}", t.toString());
            return false;
        }
    }

    /** 把对象换回原始类（换错时救场）。 */
    public static boolean restore(Object instance) {
        if (instance == null) {
            return false;
        }
        try {
            Class<?> original = ORIGINAL.get(instance);
            if (original == null) {
                return false;
            }
            boolean ok = KlassSwap.writeKlass(instance, original);
            if (ok) {
                RESTORES.incrementAndGet();
                ORIGINAL.remove(instance);
            }
            return ok;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] Klass 回退失败: {}", t.toString());
            return false;
        }
    }

    public static int swaps() {
        return SWAPS.get();
    }

    public static int restores() {
        return RESTORES.get();
    }

    public static int tracked() {
        return ORIGINAL.size();
    }
}