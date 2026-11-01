package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;
import com.maha_fish.finaljudgment.nativebridge.RedefineExecutor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 方法重定向（对应示例项目的 {@code MethodRedirect} + ArcaneVortex 的 {@code JarPatcher} 窄版）。
 *
 * <p>能力：把目标类的某个方法 <b>整体改写成固定返回值</b>，用我们自己的 JVMTI 通道
 * （{@link NativeBridge#nativeRedefineClass}）落地。</p>
 *
 * <p>典型用途：对抗"杀不死"的实体——它覆写了 {@code isAlive()} / {@code isRemoved()} /
 * {@code isInvisible()} 返回相反值，这里直接把返回值钉死。</p>
 *
 * <p><b>默认关闭</b>（{@code -Dfj.methodRedirect=true} 才生效），因为它会改写别的 mod 的类，
 * 误伤风险高；打开后也只会作用于显式传入的目标类。</p>
 */
public final class MethodRedirect {

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("fj.methodRedirect", "false"));

    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger REDIRECTS = new AtomicInteger();
    private static final AtomicInteger FAILURES = new AtomicInteger();

    private MethodRedirect() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static int redirects() {
        return REDIRECTS.get();
    }

    public static int failures() {
        return FAILURES.get();
    }

    /** 已生效的改写（供 {@link PeriodicRetransformer} 定期重新应用）。 */
    private static final java.util.List<Object[]> APPLIED = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static int applied() {
        return APPLIED.size();
    }

    /**
     * 重新应用全部已登记的改写（由 {@link PeriodicRetransformer} 每 60 秒调用）。
     *
     * <p>意义：对手 mod 可能把自己的类改回去（把我们钉死的 {@code isAlive} 又改回 true）。
     * 这里周期性重新 redefine，保证"钉死的返回值"始终有效。</p>
     */
    public static int reapplyAll() {
        if (!ENABLED || APPLIED.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Object[] entry : APPLIED) {
            try {
                Class<?> target = (Class<?>) entry[0];
                String method = (String) entry[1];
                String desc = (String) entry[2];
                boolean value = (Boolean) entry[3];
                byte[] original = readBytes(target);
                if (original == null) {
                    continue;
                }
                byte[] patched = rewrite(original, method, desc, value);
                if (patched != null && RedefineExecutor.redefine(target, patched)) {
                    count++;
                }
            } catch (Throwable ignored) {
                // 单个失败不影响其他
            }
        }
        return count;
    }

    /**
     * 把 {@code target} 的 {@code methodName + descriptor} 改写成 {@code return value}。
     *
     * @param target     目标类
     * @param methodName 方法名（MCP 或 SRG 均可，按字节码里的真实名字匹配）
     * @param descriptor JVM 描述符，例如 {@code ()Z}
     * @param value      布尔返回值（仅支持 Z 型）
     * @return 是否成功改写
     */
    public static boolean forceBooleanReturn(Class<?> target, String methodName, String descriptor, boolean value) {
        if (!ENABLED || target == null || methodName == null || descriptor == null) {
            return false;
        }
        if (!descriptor.endsWith(")Z")) {
            return false;                       // 目前只支持返回 boolean 的方法
        }
        String key = target.getName() + "#" + methodName + descriptor;
        if (!DONE.add(key)) {
            return true;                        // 已处理过
        }
        try {
            byte[] original = readBytes(target);
            if (original == null) {
                FAILURES.incrementAndGet();
                return false;
            }
            byte[] patched = rewrite(original, methodName, descriptor, value);
            if (patched == null) {
                FinalJudgment.LOGGER.debug("[FJ] MethodRedirect: 未找到目标方法 {} {}", methodName, descriptor);
                FAILURES.incrementAndGet();
                return false;
            }
            boolean ok = RedefineExecutor.redefine(target, patched);
            if (ok) {
                REDIRECTS.incrementAndGet();
                APPLIED.add(new Object[]{target, methodName, descriptor, value});
                FinalJudgment.LOGGER.info("[FJ] 方法重定向成功: {}#{} → {}",
                        target.getName(), methodName, value);
            } else {
                FAILURES.incrementAndGet();
                FinalJudgment.LOGGER.warn("[FJ] 方法重定向失败（redefine 未成功）: {}#{}", target.getName(), methodName);
            }
            return ok;
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            FinalJudgment.LOGGER.debug("[FJ] MethodRedirect 异常: {}", t.toString());
            return false;
        }
    }

    /** 常用组合：把 isAlive / isRemoved / isInvisible 三个方法钉死成"已死/已移除/不可见"。 */
    public static int neutralizeLiveness(Class<?> target) {
        if (!ENABLED || target == null) {
            return 0;
        }
        int count = 0;
        if (forceBooleanReturn(target, "isAlive", "()Z", false)) {
            count++;
        }
        if (forceBooleanReturn(target, "isRemoved", "()Z", true)) {
            count++;
        }
        if (forceBooleanReturn(target, "isInvisible", "()Z", true)) {
            count++;
        }
        return count;
    }

    private static byte[] rewrite(byte[] original, String methodName, String descriptor, boolean value) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        final boolean[] found = {false};
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor originalVisitor = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals(methodName) || !desc.equals(descriptor)) {
                    return originalVisitor;
                }
                found[0] = true;
                // 丢弃原方法体，替换为固定返回值
                return new MethodVisitor(Opcodes.ASM9, originalVisitor) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitInsn(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                        super.visitInsn(Opcodes.IRETURN);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return found[0] ? writer.toByteArray() : null;
    }

    private static byte[] readBytes(Class<?> target) {
        // 优先用 Native 抓运行时字节（已加载的类），失败再退回资源流
        try {
            byte[] runtime = NativeBridge.nativeCaptureRuntimeBytes(target.getName().replace('.', '/'));
            if (runtime != null && runtime.length > 0) {
                return runtime;
            }
        } catch (Throwable ignored) {
            // 回退
        }
        String resource = target.getName().replace('.', '/') + ".class";
        try (InputStream in = target.getClassLoader() == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : target.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (Throwable t) {
            return null;
        }
    }
    /** 内部强制通道：忽略 fj.methodRedirect 开关（仅用于击杀失败的自动中和）。 */
    public static int neutralizelivenessForced(Class<?> target) {
        if (target == null) {
            return 0;
        }
        if ("false".equalsIgnoreCase(System.getProperty("fj.methodRedirect", "true"))) {
            return 0;
        }
        int count = 0;
        if (forceBooleanReturnInternal(target, "isAlive", "()Z", false)) {
            count++;
        }
        if (forceBooleanReturnInternal(target, "isRemoved", "()Z", true)) {
            count++;
        }
        if (forceBooleanReturnInternal(target, "isInvisible", "()Z", true)) {
            count++;
        }
        return count;
    }

    /** 与 forceBooleanReturn 相同，但不受 ENABLED 开关限制。 */
    private static boolean forceBooleanReturnInternal(Class<?> target, String methodName,
                                                      String descriptor, boolean value) {
        String key = target.getName() + "#" + methodName + descriptor;
        if (!DONE.add(key)) {
            return false;
        }
        try {
            byte[] original = readBytes(target);
            if (original == null) {
                return false;
            }
            byte[] patched = rewrite(original, methodName, descriptor, value);
            if (patched == null) {
                return false;
            }
            boolean ok = RedefineExecutor.redefine(target, patched);
            if (ok) {
                REDIRECTS.incrementAndGet();
                APPLIED.add(new Object[]{target, methodName, descriptor, value});
                FinalJudgment.LOGGER.info("[FJ] 击杀失败自动中和: {}#{} -> {}",
                        target.getSimpleName(), methodName, value);
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }
    /** 供 SpecialTargets 使用的公开内部改写入口（受 fj.methodRedirect=false 控制）。 */
    public static boolean forceBooleanReturnInternalPublic(Class<?> target, String methodName,
                                                           String descriptor, boolean value) {
        if (target == null || methodName == null || descriptor == null) {
            return false;
        }
        if ("false".equalsIgnoreCase(System.getProperty("fj.methodRedirect", "true"))) {
            return false;
        }
        return forceBooleanReturnInternal(target, methodName, descriptor, value);
    }
    /**
     * 把目标方法改成"直接调用 super 的同名同签名方法"（扩展 5/6-3）。
     *
     * <p>用于让外部 Boss 对 {@code isAlive/isRemoved/hurt/die} 的覆写作废——
     * 它们自己的防御逻辑被绕过，行为回到原版。</p>
     *
     * <p>按要求使用 {@code COMPUTE_FRAMES | COMPUTE_MAXS}，否则可能 {@code VerifyError}。</p>
     */
    public static boolean rewriteToSuper(Class<?> target, String methodName, String descriptor) {
        if (target == null || methodName == null || descriptor == null) {
            return false;
        }
        if ("false".equalsIgnoreCase(System.getProperty("fj.methodRedirect", "true"))) {
            return false;
        }
        String key = target.getName() + "#super#" + methodName + descriptor;
        if (!DONE.add(key)) {
            return true;
        }
        Class<?> superType = target.getSuperclass();
        if (superType == null || superType == Object.class) {
            return false;
        }
        try {
            byte[] original = readBytes(target);
            if (original == null) {
                return false;
            }
            byte[] patched = rewriteBodyToSuper(original, superType, methodName, descriptor);
            if (patched == null) {
                return false;
            }
            boolean ok = RedefineExecutor.redefine(target, patched);
            if (ok) {
                REDIRECTS.incrementAndGet();
                APPLIED.add(new Object[]{target, methodName, descriptor, true});
                FinalJudgment.LOGGER.info("[FJ] 已改为调用父类: {}#{}{} -> super",
                        target.getSimpleName(), methodName, descriptor);
            }
            return ok;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] rewriteToSuper 失败: {}", t.toString());
            return false;
        }
    }

    private static byte[] rewriteBodyToSuper(byte[] original, Class<?> superType,
                                             String methodName, String descriptor) {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(original);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(reader,
                org.objectweb.asm.ClassWriter.COMPUTE_FRAMES | org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        final boolean[] found = {false};
        org.objectweb.asm.ClassVisitor visitor = new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
                                                               String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals(methodName) || !desc.equals(descriptor)) {
                    return mv;
                }
                found[0] = true;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);      // this
                        int slot = 1;
                        int argIndex = 0;
                        String args = desc.substring(1, desc.indexOf(')'));
                        while (argIndex < args.length()) {
                            char c = args.charAt(argIndex);
                            switch (c) {
                                case 'L', '[' -> {
                                    super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, slot);
                                    slot++;
                                    while (argIndex < args.length() && args.charAt(argIndex) != ';'
                                            && args.charAt(argIndex) != ')' && c == 'L') {
                                        argIndex++;
                                    }
                                }
                                case 'J', 'D' -> {
                                    super.visitVarInsn(c == 'J' ? org.objectweb.asm.Opcodes.LLOAD
                                            : org.objectweb.asm.Opcodes.DLOAD, slot);
                                    slot += 2;
                                }
                                case 'F' -> {
                                    super.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, slot);
                                    slot++;
                                }
                                default -> {
                                    super.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, slot);
                                    slot++;
                                }
                            }
                            argIndex++;
                        }
                        super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                                superType.getName().replace('.', '/'), methodName, desc, false);
                        String returnType = desc.substring(desc.indexOf(')') + 1);
                        switch (returnType) {
                            case "V" -> super.visitInsn(org.objectweb.asm.Opcodes.RETURN);
                            case "Z", "B", "C", "S", "I" -> super.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
                            case "J" -> super.visitInsn(org.objectweb.asm.Opcodes.LRETURN);
                            case "F" -> super.visitInsn(org.objectweb.asm.Opcodes.FRETURN);
                            case "D" -> super.visitInsn(org.objectweb.asm.Opcodes.DRETURN);
                            default -> super.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
                        }
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return found[0] ? writer.toByteArray() : null;
    }
}