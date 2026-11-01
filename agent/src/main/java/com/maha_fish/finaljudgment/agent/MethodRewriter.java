package com.maha_fish.finaljudgment.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Consumer;

/**
 * ASM 方法体改写（7.4.3 功能 2，规范 2、3）。
 *
 * <ul>
 *   <li>{@code COMPUTE_FRAMES | COMPUTE_MAXS}（插入分支必须算帧，只 COMPUTE_MAXS 会 VerifyError）</li>
 *   <li>ClassReader <b>不 SKIP_FRAMES</b></li>
 *   <li>getCommonSuperClass 用 {@link AgentClassWriter}（五级 CL 回退，失败回 Object 且不抛）</li>
 * </ul>
 * 幂等由调用方（Patch 内的"已注入"检查）负责。
 */
public final class MethodRewriter {

    private MethodRewriter() {
    }

    /**
     * @param original 原始字节
     * @param ctx      目标类的 ClassLoader（传 transform 的 loader）
     * @param mutator  就地修改 ClassNode；返回后回写
     */
    public static byte[] rewrite(byte[] original, ClassLoader ctx, Consumer<ClassNode> mutator) {
        if (original == null || original.length == 0 || mutator == null) {
            return null;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(original).accept(node, 0);   // 不 SKIP_FRAMES
            mutator.accept(node);
            ClassWriter writer = new AgentClassWriter(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, ctx);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLog.warn("MethodRewriter 失败(放弃改写): " + t);
            return null;
        }
    }
}
