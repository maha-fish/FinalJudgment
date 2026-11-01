package com.maha_fish.finaljudgment.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JarPatcher（对应示例项目的 {@code JarPatcher}，窄版实现）。
 *
 * <p>能力：在**类加载时**把其他 mod 的指定方法整体改写为固定返回值。
 * 与 {@code com.maha_fish.finaljudgment.guard.MethodRedirect}（运行期 JVMTI 改写）互补：</p>
 *
 * <ul>
 *   <li>本类：类加载期介入，对手无法"改回去"（因为它拿到的就已经是被改过的类）</li>
 *   <li>MethodRedirect：运行期介入，可对已加载的类生效</li>
 * </ul>
 *
 * <p><b>配置方式</b>（启动参数，默认空 = 完全不介入）：</p>
 * <pre>
 * -Dfj.jarPatcher=com.example.MobEntity#isAlive=false,com.example.Boss#isRemoved=true
 * </pre>
 * 格式：{@code 内部名#方法名=布尔}，多项用逗号分隔。只监听"返回 boolean 的无参方法"。
 *
 * <p><b>安全约束</b>：</p>
 * <ol>
 *   <li>只改写显式列出的类与方法，不做全量扫描</li>
 *   <li>不碰 {@code net.minecraft} / {@code net.minecraftforge} / 我们自己的包</li>
 *   <li>任何异常都放行原字节（{@link MainClassTransformer} 已有兜底）</li>
 * </ol>
 */
public final class JarPatcher {

    private static final String PROPERTY = "fj.jarPatcher";

    /** 单条规则：内部名 + 方法名 + 期望返回值。 */
    private static final List<Rule> RULES = new ArrayList<>();
    private static final AtomicInteger PATCHED = new AtomicInteger();

    private JarPatcher() {
    }

    private static final class Rule {
        final String internalName;
        final String methodName;
        final boolean value;

        Rule(String internalName, String methodName, boolean value) {
            this.internalName = internalName;
            this.methodName = methodName;
            this.value = value;
        }
    }

    /** 解析启动参数并注册 Patch（由 agent 启动阶段调用一次）。 */
    public static void install() {
        String raw = System.getProperty(PROPERTY, "").trim();
        if (raw.isEmpty()) {
            AgentLog.info("JarPatcher 未启用（-D" + PROPERTY + " 为空）");
            return;
        }
        for (String item : raw.split(",")) {
            String entry = item.trim();
            if (entry.isEmpty()) {
                continue;
            }
            try {
                int hash = entry.indexOf('#');
                int eq = entry.lastIndexOf('=');
                if (hash <= 0 || eq <= hash) {
                    AgentLog.warn("JarPatcher 规则格式错误（应为 类#方法=布尔）: " + entry);
                    continue;
                }
                String internalName = entry.substring(0, hash).trim().replace('.', '/');
                String methodName = entry.substring(hash + 1, eq).trim();
                boolean value = Boolean.parseBoolean(entry.substring(eq + 1).trim());
                if (isProtected(internalName)) {
                    AgentLog.warn("JarPatcher 拒绝改写受保护类: " + internalName);
                    continue;
                }
                RULES.add(new Rule(internalName, methodName, value));
                AgentLog.info("JarPatcher 规则: " + internalName + "#" + methodName + " -> " + value);
            } catch (Throwable t) {
                AgentLog.warn("JarPatcher 规则解析失败: " + entry + " (" + t + ")");
            }
        }
        for (Rule rule : RULES) {
            MainClassTransformer.register(rule.internalName, (loader, internalName, classNode) ->
                    patchClass(classNode, rule));
        }
        AgentLog.info("JarPatcher 已注册 " + RULES.size() + " 条规则");
    }

    /** 实际改写：把匹配的方法体替换成 {@code return value}。 */
    private static boolean patchClass(ClassNode classNode, Rule rule) {
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(rule.methodName)) {
                continue;
            }
            if (!"()Z".equals(method.desc)) {
                continue;                        // 只处理返回 boolean 的无参方法
            }
            method.instructions.clear();
            method.instructions.add(new InsnNode(rule.value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            method.tryCatchBlocks = new ArrayList<>();
            method.localVariables = null;
            changed = true;
            PATCHED.incrementAndGet();
            AgentLog.info("JarPatcher 命中: " + classNode.name + "#" + method.name + " -> " + rule.value);
        }
        return changed;
    }

    /** 不允许改写的包（核心/我们自己的）。 */
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

    public static int rules() {
        return RULES.size();
    }
}
