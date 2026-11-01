package com.maha_fish.finaljudgment;

import com.maha_fish.finaljudgment.common.Constants;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 初始化调度：严格按 8.2 的 25 步执行。每步独立 try-catch（8.3），
 * 任一步失败只打 WARN，不阻塞后续步骤（规范 40/46）。
 */
public final class InitPipeline {

    private static final String ENTITY = "net.minecraft.world.entity.Entity";
    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";

    private static boolean nativeReady;

    private InitPipeline() {
    }

    /**
     * 把 SRG 候选表发布进系统属性（{@code fj.srg.<KEY> = "a|b|c"}）。
     *
     * <p>为什么需要：coremod 的 transformer 在类变换期拿到的 context ClassLoader
     * 看不到主 mod 的类（层级隔离），反射 common 的 SRGNames 会 ClassNotFoundException。
     * 系统属性是进程全局的，天然跨层。由 {@link FinalJudgment} 构造器调用（早于 step 10 的 retransform）。</p>
     */
    public static void publishSrgTable() {
        int published = 0;
        try {
            for (java.lang.reflect.Field field : com.maha_fish.finaljudgment.common.SRGNames.class.getFields()) {
                if (field.getType() != String[].class) {
                    continue;
                }
                String[] value = (String[]) field.get(null);
                if (value == null || value.length == 0) {
                    continue;
                }
                System.setProperty("fj.srg." + field.getName(), String.join("|", value));
                published++;
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] SRG 候选表发布失败: {}", t.toString());
            return;
        }
        FinalJudgment.LOGGER.info("[FJ] SRG 候选表已发布到系统属性（{} 组）", published);
    }

    public static void run() {
        long begin = System.currentTimeMillis();
        FinalJudgment.LOGGER.info("[FJ] ===== 初始化开始（8.2 的 25 步）=====");

        step(1, "NativeBridge.load + 自检", () -> nativeReady = NativeBridge.load());
        step(2, "Native 能力位查询 + 日志", () -> {
            if (nativeReady) {
                FinalJudgment.LOGGER.info("[FJ-Native] 能力位=0x{}", Integer.toHexString(NativeBridge.nativeCapabilities()));
            }
        });
        step(3, "Native retransform 测试（仅开发环境）", () -> {
            if (!FMLEnvironment.production && nativeReady) {
                int ok = NativeBridge.nativeRetransformClasses(new Class<?>[]{InitPipeline.class});
                FinalJudgment.LOGGER.info("[FJ-Native] 开发环境 retransform 测试: 成功 {}", ok);
            }
        });
        step(4, "注册 Native 日志桥接", () ->
                FinalJudgment.LOGGER.info("[FJ-Native] 日志桥接已挂载（在 load 阶段完成）"));
        step(5, "注册 Native 守卫回调", () -> optional("com.maha_fish.finaljudgment.guard.NativeGuard",
                type -> NativeBridge.nativeInitGuard(type), "Unit 8"));
        step(6, "反射审计（-Dfj.reflection.audit=true）", () -> {
            if (Boolean.getBoolean(Constants.SYS_REFLECTION_AUDIT)) {
                optional("com.maha_fish.finaljudgment.core.ReflectionAudit",
                        type -> invokeStatic(type, "run"), "Unit 7c");
            }
        });
        step(7, "Agent 探测", () ->
                FinalJudgment.LOGGER.info("[FJ-Agent] 探测结果: ready={}", AgentBridge.isReady()));
        step(8, "Agent 附加", AgentAttacher::attachFromResource);
        step(9, "确认 MainClassTransformer 已注册（agent 侧）", () -> {
            if (AgentBridge.isReady()) {
                FinalJudgment.LOGGER.info("[FJ-Agent] MainClassTransformer 随 agent 启动已注册");
            }
        });
        step(10, "retransform LivingEntity（Agent 层）", InitPipeline::retransformCoreClasses);
        step(11, "PeriodicRetransformer（agent 侧已启动）", () ->
                FinalJudgment.LOGGER.info("[FJ-Agent] 定时 retransform 由 agent 侧承担（D28）"));
        step(12, "ForcedClassRewrite（agent 侧已执行）", () ->
                FinalJudgment.LOGGER.info("[FJ-Agent] ForcedClassRewrite 由 agent 侧执行（D19）"));
        subStep("12.5", "Native 补扫（D7）", InitPipeline::nativeBackfill);
        step(13, "NativeGuard.install（必须在 10/12 之后，规范 29）",
                () -> com.maha_fish.finaljudgment.guard.NativeGuard.install());

        step(14, "T0Anchor（由 7a 的静态工具直接提供，无需 init）", () ->
                FinalJudgment.LOGGER.debug("[FJ] T0Anchor 为纯静态工具类，跳过初始化"));
        step(15, "AttackEventHandler.init", () -> {
            com.maha_fish.finaljudgment.defense.EventBusGuard.register(
                    new com.maha_fish.finaljudgment.attack.EntityRegistry());
            com.maha_fish.finaljudgment.defense.EventBusGuard.register(
                    new com.maha_fish.finaljudgment.attack.AttackEventHandler());
            com.maha_fish.finaljudgment.attack.AttackEventHandler.init();
        });
        step(16, "DefenseHandler.init", () -> {
            com.maha_fish.finaljudgment.defense.EventBusGuard.register(
                    new com.maha_fish.finaljudgment.defense.DefenseHandler());
            com.maha_fish.finaljudgment.defense.EventBusGuard.register(
                    new com.maha_fish.finaljudgment.defense.DisarmListener());
            com.maha_fish.finaljudgment.defense.DefenseHandler.init();
            com.maha_fish.finaljudgment.defense.AntiDisarm.init();
        });
        step(17, "EventBusAnchor.init（金丝雀监听器守护）", () ->
                com.maha_fish.finaljudgment.defense.EventBusAnchor.init());
        step(18, "EventBusGuard.init", () ->
                com.maha_fish.finaljudgment.defense.EventBusGuard.init());
        step(19, "IsolationManager.init", () ->
                com.maha_fish.finaljudgment.core.IsolationManager.init());
        step(20, "EntityListGuard.init", () ->
                com.maha_fish.finaljudgment.core.EntityListGuard.init());
        step(21, "LoopHijacker.init（LOWEST + receiveCanceled + END）", () -> {
            com.maha_fish.finaljudgment.core.LoopHijacker hijacker =
                    new com.maha_fish.finaljudgment.core.LoopHijacker();
            com.maha_fish.finaljudgment.defense.EventBusGuard.register(hijacker);
            com.maha_fish.finaljudgment.core.LoopHijacker.init();
            com.maha_fish.finaljudgment.defense.EventBusGuard.register(
                    new com.maha_fish.finaljudgment.core.LogoutListener());
        });
        step(22, "HeartbeatThread.start", () ->
                com.maha_fish.finaljudgment.core.HeartbeatThread.start());
        step(23, "ServerAutoAttack.init", () ->
                com.maha_fish.finaljudgment.attack.ServerAutoAttack.init());
        step(24, "三条 Native 路线（Bootstrap → Analysis → Redefine）", () ->
                com.maha_fish.finaljudgment.nativebridge.NativeRoutes.run());
        step(25, "Client 模块（由 @Mod.EventBusSubscriber 自动注册）", () ->
                FinalJudgment.LOGGER.debug("[FJ] Client 侧类由 Forge 自动注册，跳过初始化"));

        LayerStatus.logSnapshot();
        FinalJudgment.LOGGER.info("[FJ] ===== 初始化结束（{} ms）=====", System.currentTimeMillis() - begin);
    }

    private static void retransformCoreClasses() throws Exception {
        Class<?>[] targets = resolveClasses(ENTITY, LIVING_ENTITY);
        if (targets.length == 0) {
            FinalJudgment.LOGGER.warn("[FJ-Agent] 目标类未加载，跳过 retransform");
            return;
        }
        Object instrumentation = AgentBridge.getInstrumentation();
        if (instrumentation == null) {
            FinalJudgment.LOGGER.warn("[FJ-Agent] 无 Instrumentation（Agent 未就绪），跳过");
            return;
        }
        Class<?> instInterface = Class.forName("java.lang.instrument.Instrumentation");
        Method retransform = instInterface.getMethod("retransformClasses", Class[].class);
        retransform.invoke(instrumentation, (Object) targets);
        FinalJudgment.LOGGER.info("[FJ-Agent] 已对 {} 个核心类触发 retransform", targets.length);
    }

    private static void nativeBackfill() throws Exception {
        if (!nativeReady) {
            FinalJudgment.LOGGER.info("[FJ-Native] 补扫跳过（Native 未就绪）");
            return;
        }
        Class<?>[] targets = resolveClasses(ENTITY, LIVING_ENTITY);
        int ok = NativeBridge.nativeRetransformClasses(targets);
        FinalJudgment.LOGGER.info("[FJ-Native] 补扫: 目标 {} 个，成功 {}", targets.length, ok);
    }

    private static Class<?>[] resolveClasses(String... names) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class<?>[] resolved = new Class<?>[names.length];
        int count = 0;
        for (String name : names) {
            try {
                resolved[count] = Class.forName(name, false, loader);
                count++;
            } catch (Throwable ignored) {
                // 该类当前不可见
            }
        }
        Class<?>[] trimmed = new Class<?>[count];
        System.arraycopy(resolved, 0, trimmed, 0, count);
        return trimmed;
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void step(int index, String name, Body body) {
        long start = System.currentTimeMillis();
        try {
            body.run();
            FinalJudgment.LOGGER.info("[FJ] 步骤 {}/25 {} 完成（{} ms）",
                    index, name, System.currentTimeMillis() - start);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] 步骤 {}/25 {} 失败: {}", index, name, t.toString());
        }
    }

    private static void subStep(String label, String name, Body body) {
        try {
            body.run();
            FinalJudgment.LOGGER.info("[FJ] 步骤 {} {} 完成", label, name);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] 步骤 {} {} 失败: {}", label, name, t.toString());
        }
    }

    private static void pending(int index, String name, String unit) {
        FinalJudgment.LOGGER.info("[FJ] 步骤 {}/25 {} 待实现（{}）", index, name, unit);
    }

    private static void optional(String className, Consumer<Class<?>> action, String owner) {
        try {
            Class<?> type = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            action.accept(type);
        } catch (ClassNotFoundException e) {
            FinalJudgment.LOGGER.info("[FJ] {} 尚未实现（{}），跳过", className, owner);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void invokeStatic(Class<?> type, String method) {
        try {
            type.getMethod(method).invoke(null);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
