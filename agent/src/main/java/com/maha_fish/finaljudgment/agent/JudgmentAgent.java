package com.maha_fish.finaljudgment.agent;

import java.lang.instrument.Instrumentation;

/**
 * Agent 入口：MANIFEST 三件套里的 Premain-Class / Agent-Class（规范 22、ENV_SPEC 4.3）。
 *
 * <p>premain（-javaagent 或启动早期）与 agentmain（子 JVM 附加后）共用同一段启动逻辑。</p>
 *
 * <p>3a 只交付「入口 + 持有者 + 附加器 + 日志 + 我方标记」；
 * MainClassTransformer 注册、PeriodicRetransformer、ForcedClassRewrite 在 3b 接入。</p>
 */
public final class JudgmentAgent {

    private static volatile String[] targets = new String[0];
    private static volatile String phase = "unknown";

    private JudgmentAgent() {
    }

    /** -javaagent 路径（OnLoad 阶段，能力最全）。 */
    public static void premain(String agentArgs, Instrumentation inst) {
        start("premain", agentArgs, inst);
    }

    /** 动态附加路径（子 JVM VirtualMachine.loadAgent 触发）。 */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        start("agentmain", agentArgs, inst);
    }

    private static void start(String phaseName, String agentArgs, Instrumentation inst) {
        phase = phaseName;
        if (inst == null) {
            AgentLog.warn(phaseName + " 收到 null Instrumentation，放弃初始化");
            return;
        }

        InstHolder.set(inst);
        targets = parseTargets(agentArgs);

        AgentLog.info(phaseName + " 已就绪: retransform=" + inst.isRetransformClassesSupported()
                + " redefine=" + inst.isRedefineClassesSupported()
                + " 目标类数=" + targets.length);

        if (!inst.isRetransformClassesSupported()) {
            AgentLog.warn("不支持 retransformClasses，Agent 层降级：不注册 transformer、不起定时器");
            return;
        }

        // 8.2 步骤 9：注册主 transformer（canRetransform=true）
        inst.addTransformer(new MainClassTransformer(), true);
        AgentLog.info("MainClassTransformer 已注册 (canRetransform=true)");

        // Unit 7b：把 LivingEntity 的守卫改写注册进 Patch 表（必须在类加载前登记，错过则靠周期 retransform 兜底）
        MainClassTransformer.register(MainTransformer.TARGET, new MainTransformer());
        // JarPatcher：按 -Dfj.jarPatcher 规则，在类加载期改写对手的方法（默认空 = 不介入）
        try {
            JarPatcher.install();
        } catch (Throwable t) {
            AgentLog.warn("JarPatcher 安装失败: " + t);
        }
        // MixinPatcher：按 -Dfj.mixinPatcher 列出的 Mixin 类，中和其注入方法体（默认空 = 不介入）
        try {
            MixinPatcher.install();
        } catch (Throwable t) {
            AgentLog.warn("MixinPatcher 安装失败: " + t);
        }
        System.setProperty("fj.agent.patch.registered", "true");
        AgentLog.info("MainTransformer 已登记到 Patch 表: " + MainTransformer.TARGET);

        // 8.2 步骤 11：周期 retransform
        PeriodicRetransformer.start(inst);

        // 8.2 步骤 12：ForcedClassRewrite —— agentmain 时类已加载，先试一次；
        // premain 早期会失败，由周期 tick 重试补齐（D19）
        if ("agentmain".equals(phaseName)) {
            ForcedClassRewrite.ensure(inst);
        }
    }

    /** 3b 使用：agentArgs 为逗号分隔的目标类内部名。 */
    static String[] targets() {
        return targets;
    }

    static String phase() {
        return phase;
    }

    private static String[] parseTargets(String agentArgs) {
        if (agentArgs == null || agentArgs.isBlank()) {
            return new String[0];
        }
        String[] parts = agentArgs.split(",");
        int count = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                count++;
            }
        }
        String[] result = new String[count];
        int index = 0;
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) {
                result[index++] = value;
            }
        }
        return result;
    }
}
