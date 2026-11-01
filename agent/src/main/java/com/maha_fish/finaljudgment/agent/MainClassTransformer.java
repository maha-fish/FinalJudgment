package com.maha_fish.finaljudgment.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主 ClassFileTransformer（4.2 功能 3、7.4.3 功能 3）。
 *
 * <p>职责：按内部名精确匹配 → 交给注册的 {@link Patch} 改写 → 用
 * {@link AgentClassWriter}（COMPUTE_FRAMES | COMPUTE_MAXS）回写。
 * <b>任何异常都返回 null 放行原字节</b>，绝不让类加载失败。</p>
 *
 * <p>幂等（D18）：由 patch 负责——{@code patch()} 返回 {@code false} 表示「已注入过/无需改动」。
 * 因为每次 transform 都从入参字节重建，重复执行同一 patch 不会叠加。</p>
 */
public final class MainClassTransformer implements ClassFileTransformer {

    /** 方法体改写插拔口（7b 的 LivingEntityPatch 实现它）。 */
    public interface Patch {
        /**
         * 就地改写 ClassNode。
         *
         * @return true 表示有改动（需要回写）；false 表示已注入过/无需改动
         */
        boolean patch(ClassLoader loader, String internalName, ClassNode classNode) throws Throwable;
    }

    private static final Map<String, Patch> PATCHES = new ConcurrentHashMap<>();
    private static final AtomicBoolean FIRST_APPLIED = new AtomicBoolean();

    /** 主 mod ClassLoader 候选：首次成功改写时捕获（供 AgentClassWriter 第 ③ 级使用）。 */
    private static volatile ClassLoader lastModLoader;

    public static void register(String internalName, Patch patch) {
        if (internalName == null || patch == null) {
            return;
        }
        PATCHES.put(internalName, patch);
        AgentLog.info("Patch 已注册: " + internalName + " -> " + patch.getClass().getName());
    }

    public static void clear() {
        PATCHES.clear();
    }

    static ClassLoader lastModLoader() {
        return lastModLoader;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        Patch patch = PATCHES.get(className);
        if (patch == null) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode();
            // 规范 3 同理：不 SKIP_FRAMES，保证 FrameNode 完整
            reader.accept(node, 0);

            if (!patch.patch(loader, className, node)) {
                return null;
            }

            ClassWriter writer = new AgentClassWriter(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            node.accept(writer);
            byte[] transformed = writer.toByteArray();

            if (lastModLoader == null) {
                lastModLoader = loader;
            }
            if (FIRST_APPLIED.compareAndSet(false, true)) {
                AgentLog.info("transformer 生效: " + className + " -> " + transformed.length
                        + " 字节 (loader=" + loader + ")");
            }
            return transformed;
        } catch (Throwable t) {
            AgentLog.warn("transform 失败(放行原字节): " + className + " (" + t + ")");
            return null;
        }
    }
}
