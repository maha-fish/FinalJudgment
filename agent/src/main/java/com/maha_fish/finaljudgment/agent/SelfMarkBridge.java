package com.maha_fish.finaljudgment.agent;

import com.maha_fish.finaljudgment.common.Constants;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「我方 retransform 标记」的跨 ClassLoader 降级实现（规范 44 / 规范 12）。
 *
 * <p>Agent 由 System ClassLoader 加载，不能直接引用主 mod 的 NativeBridge，
 * 因此按四级优先级逐级回退，并把<b>实际命中的级别</b>打进日志（供自检 4 取证）：</p>
 *
 * <ol>
 *   <li>反射 Native 计数器 {@code NativeBridge.nativeSetSelfRetransform}（context ClassLoader）</li>
 *   <li>Bootstrap 共享 {@code fj.bootstrap.SelfMark.COUNT}（AtomicInteger）</li>
 *   <li>系统属性 {@code fj.self.retransform}（非原子，明确降级）</li>
 *   <li>{@link ThreadLocal}（对本用途无效，仅占位；只有第 3 级被安全管理器拒绝时才会命中）</li>
 * </ol>
 */
public final class SelfMarkBridge {

    public static final int LEVEL_UNRESOLVED = 0;
    public static final int LEVEL_NATIVE = 1;
    public static final int LEVEL_BOOTSTRAP = 2;
    public static final int LEVEL_PROPERTY = 3;
    public static final int LEVEL_THREAD_LOCAL = 4;

    private static final String NATIVE_CLASS = "com.maha_fish.finaljudgment.nativebridge.NativeBridge";
    private static final String NATIVE_METHOD = "nativeSetSelfRetransform";
    private static final String BOOTSTRAP_CLASS = "fj.bootstrap.SelfMark";
    private static final String BOOTSTRAP_FIELD = "COUNT";
    private static final String PROPERTY_KEY = Constants.SYS_SELF_RETRANSFORM;

    private static final ThreadLocal<Boolean> THREAD_LOCAL = new ThreadLocal<>();

    private static volatile int level = LEVEL_UNRESOLVED;
    private static volatile Method nativeMethod;
    private static volatile AtomicInteger bootstrapCounter;

    private SelfMarkBridge() {
    }

    public static void enter() {
        apply(true);
    }

    public static void exit() {
        apply(false);
    }

    /** 当前生效的降级级别（0 表示尚未解析）。 */
    public static int level() {
        resolve();
        return level;
    }

    private static void apply(boolean active) {
        resolve();
        switch (level) {
            case LEVEL_NATIVE:
                invokeNative(active);
                break;
            case LEVEL_BOOTSTRAP:
                AtomicInteger counter = bootstrapCounter;
                if (counter != null) {
                    if (active) {
                        counter.incrementAndGet();
                    } else {
                        counter.decrementAndGet();
                    }
                }
                break;
            case LEVEL_PROPERTY:
                try {
                    System.setProperty(PROPERTY_KEY, active ? "1" : "0");
                } catch (Throwable t) {
                    level = LEVEL_THREAD_LOCAL;
                    THREAD_LOCAL.set(active);
                }
                break;
            default:
                THREAD_LOCAL.set(active);
                break;
        }
    }

    private static void invokeNative(boolean active) {
        Method method = nativeMethod;
        if (method == null) {
            return;
        }
        try {
            method.invoke(null, active);
        } catch (Throwable t) {
            // Native 暂时不可用：下次重新解析，避免永久卡在失败状态
            nativeMethod = null;
            level = LEVEL_UNRESOLVED;
        }
    }

    private static synchronized void resolve() {
        if (level != LEVEL_UNRESOLVED) {
            return;
        }

        // 级别 1：Native 计数器
        try {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context == null) {
                context = ClassLoader.getSystemClassLoader();
            }
            Class<?> nativeClass = Class.forName(NATIVE_CLASS, true, context);
            nativeMethod = nativeClass.getMethod(NATIVE_METHOD, boolean.class);
            level = LEVEL_NATIVE;
            AgentLog.info("我方标记降级级别=1 (Native 计数器)");
            return;
        } catch (Throwable ignored) {
            // 继续降级
        }

        // 级别 2：Bootstrap 共享 AtomicInteger
        try {
            Class<?> bootstrapClass = Class.forName(BOOTSTRAP_CLASS, false, null);
            Field field = bootstrapClass.getField(BOOTSTRAP_FIELD);
            Object value = field.get(null);
            if (value instanceof AtomicInteger) {
                bootstrapCounter = (AtomicInteger) value;
                level = LEVEL_BOOTSTRAP;
                AgentLog.info("我方标记降级级别=2 (Bootstrap AtomicInteger)");
                return;
            }
        } catch (Throwable ignored) {
            // 继续降级
        }

        // 级别 3：系统属性（非原子，明确降级）
        level = LEVEL_PROPERTY;
        AgentLog.warn("我方标记降级级别=3 (系统属性，跨线程不原子)");
    }
}
