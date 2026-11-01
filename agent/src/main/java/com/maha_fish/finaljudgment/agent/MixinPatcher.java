package com.maha_fish.finaljudgment.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MixinPatcher（对应示例项目的 {@code MixinPatcher}，保守实现）。
 *
 * <p>目标：让指定 mod 的 <b>Mixin 注入代码彻底失效</b>，但<b>不动目标类的原有方法</b>。</p>
 *
 * <p>做法（关键在"只动注入方法，不动原方法"）：</p>
 * <ol>
 *   <li>抓到配置里列出的 Mixin 类</li>
 *   <li>只扫描带 {@code @Inject / @Redirect / @Overwrite / @ModifyVariable / @ModifyArg /
 *       @ModifyConstant / @ModifyExpressionValue / @WrapOperation / @Accessor / @Invoker}
 *       注解的方法</li>
 *   <li>把这些方法的<b>方法体清空</b>为默认返回（void→return，boolean→false，数值→0，
 *       对象→null）。Mixin 依然会"应用"，但注入的代码什么都不做</li>
 *   <li>不带注入注解的方法一律不碰（避免破坏 Mixin 自身的辅助逻辑）</li>
 * </ol>
 *
 * <p><b>配置</b>（启动参数，默认空 = 不介入）：</p>
 * <pre>
 * -Dfj.mixinPatcher=com.example.mixin.MobHealthMixin,com.example.mixin.AnotherMixin
 * </pre>
 * 用类名（点或斜杠均可），多项逗号分隔。
 *
 * <p><b>安全约束</b>：拒绝改写 {@code org/spongepowered}、{@code net/minecraft}、
 * {@code net/minecraftforge}、{@code com/mojang} 与自身包；任何异常放行原字节。</p>
 */
public final class MixinPatcher {

    private static final String PROPERTY = "fj.mixinPatcher";

    /** Mixin 注入注解的包前缀（只看这些，判定"这是注入方法"）。 */
    private static final String[] INJECTION_PREFIXES = {
            "Lorg/spongepowered/asm/mixin/injection/",
            "Lorg/spongepowered/asm/mixin/gen/"
    };

    private static final List<String> TARGETS = new ArrayList<>();
    private static final AtomicInteger PATCHED = new AtomicInteger();

    private MixinPatcher() {
    }

    /** 解析启动参数并注册 Patch（agent 启动阶段调用一次）。 */
    public static void install() {
        String raw = System.getProperty(PROPERTY, "").trim();
        if (raw.isEmpty()) {
            AgentLog.info("MixinPatcher 未启用（-D" + PROPERTY + " 为空）");
            return;
        }
        for (String item : raw.split(",")) {
            String internalName = item.trim().replace('.', '/');
            if (internalName.isEmpty()) {
                continue;
            }
            if (isProtected(internalName)) {
                AgentLog.warn("MixinPatcher 拒绝改写受保护类: " + internalName);
                continue;
            }
            TARGETS.add(internalName);
            AgentLog.info("MixinPatcher 目标: " + internalName);
        }
        for (String target : TARGETS) {
            MainClassTransformer.register(target, (loader, internalName, classNode) ->
                    neutralize(classNode, internalName));
        }
        AgentLog.info("MixinPatcher 已注册 " + TARGETS.size() + " 个目标");
    }

    /** 只清空"带注入注解"的方法体。 */
    private static boolean neutralize(ClassNode classNode, String internalName) {
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (!isInjectionMethod(method)) {
                continue;
            }
            defaultReturn(method);
            changed = true;
            PATCHED.incrementAndGet();
            AgentLog.info("MixinPatcher 已中和注入: " + internalName + "#" + method.name + method.desc);
        }
        if (changed) {
            AgentLog.info("MixinPatcher 命中类: " + internalName);
        }
        return changed;
    }

    private static boolean isInjectionMethod(MethodNode method) {
        if (method.invisibleAnnotations != null && hasInjectionAnnotation(method.invisibleAnnotations)) {
            return true;
        }
        return method.visibleAnnotations != null && hasInjectionAnnotation(method.visibleAnnotations);
    }

    private static boolean hasInjectionAnnotation(List<AnnotationNode> annotations) {
        for (AnnotationNode annotation : annotations) {
            if (annotation == null || annotation.desc == null) {
                continue;
            }
            for (String prefix : INJECTION_PREFIXES) {
                if (annotation.desc.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void defaultReturn(MethodNode method) {
        method.instructions.clear();
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks = new ArrayList<>();
        }
        method.localVariables = null;
        String returnType = method.desc.substring(method.desc.indexOf(')') + 1);
        switch (returnType) {
            case "V" -> method.instructions.add(new InsnNode(Opcodes.RETURN));
            case "Z", "B", "C", "S", "I" -> {
                method.instructions.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
            }
            case "J" -> {
                method.instructions.add(new InsnNode(Opcodes.LCONST_0));
                method.instructions.add(new InsnNode(Opcodes.LRETURN));
            }
            case "F" -> {
                method.instructions.add(new InsnNode(Opcodes.FCONST_0));
                method.instructions.add(new InsnNode(Opcodes.FRETURN));
            }
            case "D" -> {
                method.instructions.add(new InsnNode(Opcodes.DCONST_0));
                method.instructions.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }

    private static boolean isProtected(String internalName) {
        return internalName.startsWith("net/minecraft/")
                || internalName.startsWith("net/minecraftforge/")
                || internalName.startsWith("com/mojang/")
                || internalName.startsWith("org/spongepowered/")
                || internalName.startsWith("com/maha_fish/finaljudgment/");
    }

    public static int patched() {
        return PATCHED.get();
    }

    public static int targets() {
        return TARGETS.size();
    }
}
