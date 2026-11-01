package com.maha_fish.finaljudgment.agent;

import org.objectweb.asm.ClassWriter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ASM ClassWriter：{@link #getCommonSuperClass} 用【多级 ClassLoader 回退】解析类（规范 2 的加强版）。
 *
 * <p>解析顺序：① transform 的 loader → ② context ClassLoader → ③ 主 mod CL（首次成功改写时捕获）
 * → ④ System ClassLoader → ⑤ Bootstrap（null）。<b>全部失败</b>才回退 {@code java/lang/Object}
 * 并打一次 WARN；任何异常都不向外抛，否则 COMPUTE_FRAMES 会把类加载搞崩。</p>
 */
public final class AgentClassWriter extends ClassWriter {

    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean();

    private final ClassLoader transformLoader;

    public AgentClassWriter(int flags, ClassLoader transformLoader) {
        super(flags);
        this.transformLoader = transformLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        Class<?> first = resolve(type1);
        Class<?> second = resolve(type2);
        if (first == null || second == null) {
            warnFallback(type1, type2, first == null ? type1 : type2);
            return "java/lang/Object";
        }
        try {
            if (first.isAssignableFrom(second)) {
                return type1;
            }
            if (second.isAssignableFrom(first)) {
                return type2;
            }
            if (first.isInterface() || second.isInterface()) {
                return "java/lang/Object";
            }
            Class<?> current = first;
            while (current != null && !current.isAssignableFrom(second)) {
                current = current.getSuperclass();
            }
            return current == null ? "java/lang/Object" : current.getName().replace('.', '/');
        } catch (Throwable t) {
            warnFallback(type1, type2, t.toString());
            return "java/lang/Object";
        }
    }

    /** 五级回退解析：任一级命中即返回。 */
    private Class<?> resolve(String internalName) {
        String binaryName = internalName.replace('/', '.');
        for (ClassLoader candidate : candidateLoaders()) {
            try {
                return Class.forName(binaryName, false, candidate);
            } catch (Throwable ignored) {
                // 换下一级
            }
        }
        return null;
    }

    private ClassLoader[] candidateLoaders() {
        return new ClassLoader[]{
                transformLoader,                                 // ① transform 的 loader
                Thread.currentThread().getContextClassLoader(),   // ② context CL
                MainClassTransformer.lastModLoader(),             // ③ 主 mod CL（已捕获）
                ClassLoader.getSystemClassLoader(),               // ④ System CL
                null                                              // ⑤ Bootstrap
        };
    }

    private static void warnFallback(String type1, String type2, String detail) {
        if (!FALLBACK_WARNED.compareAndSet(false, true)) {
            return;
        }
        AgentLog.warn("getCommonSuperClass 五级 CL 均未命中，回退 java/lang/Object: "
                + type1 + " / " + type2 + " （细节: " + detail + "）");
    }
}
