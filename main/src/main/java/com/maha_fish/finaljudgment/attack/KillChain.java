package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.EntityReinjector;
import com.maha_fish.finaljudgment.core.FieldWriter;
import com.maha_fish.finaljudgment.KillFlagBridge;
import com.maha_fish.finaljudgment.core.MapSweep;
import com.maha_fish.finaljudgment.core.StorageExtract;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;
import com.maha_fish.finaljudgment.nativebridge.NativeFieldBorrower;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;

/**
 * 击杀链（7.5 功能 4，规范 8/16）。
 *
 * <p><b>6 段顺序</b>：改逻辑 → 改状态 → 写字段 → 反射移除 → 弱 Java 兜底 → 借用。
 * <b>16 条路径按强度降序</b>，每条独立 try-catch，每条后调 {@link KillValidator#isGone}。</p>
 *
 * <p>默认 <b>全部路径都跑一遍</b>（D42，对抗环境规则；`-Dfj.kill.allPaths=false` 可改为"见好就收"）。</p>
 */
public final class KillChain {

    private static final boolean ALL_PATHS =
            !"false".equalsIgnoreCase(System.getProperty("fj.kill.allPaths", "true"));

    private KillChain() {
    }

    /** 前置准备：6 项（掉落/图腾在 9d，这里先做能做的 3 项）。 */
    public static void prepare(ServerLevel level, Entity target) {
        try {
            KillFlagBridge.setTargetEntity(target);
            KillRegistry.mark(target);
            if (target instanceof LivingEntity living && living.getHealth() > 0.0F) {
                living.setHealth(0.0F);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 击杀前置准备异常: {}", t.toString());
        }
    }

    /**
     * 执行击杀链。
     *
     * @return 最终是否消失
     */
    public static boolean execute(ServerLevel level, Entity target) {
        if (level == null || target == null) {
            return false;
        }
        prepare(level, target);
        long start = System.currentTimeMillis();
        int attempted = 0;
        for (int path = 1; path <= 16; path++) {
            attempted++;
            try {
                act(path, level, target);
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] 路径 {} 异常: {}", path, t.toString());
            }
            boolean gone = KillValidator.isGone(target);
            // 日志瘦身：逐路径结果不再输出（需要时用 -Dfj.log.verbose=true 排查）
            if (gone) {
                FinalJudgment.LOGGER.info("[FJ] 击杀链: 路径{} {} 成功 mask={} 用时={}ms",
                        path, name(path), KillValidator.mask(target), System.currentTimeMillis() - start);
                // 关键：索引摘除后服务端不会再发移除包 → 走参考实现的"结构性移除"（含移除包）
                EntityRemover.remove(level, target);
                if (!ALL_PATHS) {
                    return true;
                }
            }
        }
        boolean gone = KillValidator.isGone(target);
        if (gone) {
            KillPressure.activate(level, target);   // 已消失也进压力窗口，确认 5 tick 稳定
        }
        FinalJudgment.LOGGER.info("[FJ] 击杀链结果: {} 尝试路径={} mask={} 用时={}ms",
                gone ? "DONE" : "未成功", attempted, KillValidator.mask(target),
                System.currentTimeMillis() - start);
        return gone;
    }

    private static void act(int path, ServerLevel level, Entity target) throws Exception {
        switch (path) {
            // ===== 第 1 段：改逻辑 =====
            case 1 -> nativeRedefineAll(target);
            // ===== 第 2 段：改状态 =====
            case 2 -> nativeKill(target);
            case 3 -> unsafeWriteRemoval(target);
            case 4 -> mixinAccessorWrite(target);
            case 5 -> StorageExtract.extract(level, target);
            case 6 -> MapSweep.sweep(level, target, target.getUUID());
            case 7 -> StorageExtract.extract(level, target);          // DirectChunkMapRemove（含在 5 里，重复执行无害）
            case 8 -> entityManagerRemove(level, target);
            // ===== 第 3 段：写字段 =====
            case 9 -> mixinInvokerCall(target);
            // ===== 第 4 段：反射移除 =====
            case 10 -> target.setRemoved(Entity.RemovalReason.KILLED);
            case 11 -> target.remove(Entity.RemovalReason.KILLED);
            case 12 -> target.discard();
            // ===== 第 5 段：弱 Java 兜底 =====
            case 13 -> {
                if (target instanceof LivingEntity living) {
                    living.setHealth(0.0F);
                }
                target.setRemoved(Entity.RemovalReason.KILLED);
            }
            case 14 -> {
                if (target instanceof LivingEntity living) {
                    living.setHealth(0.0F);
                    living.kill();
                }
            }
            // ===== 第 6 段：借用 =====
            case 15 -> NativeFieldBorrower.borrowSetter(target,
                    new String[]{"setRemoved", "m_142467_"}, Entity.RemovalReason.KILLED);
            case 16 -> NativeFieldBorrower.borrowGetter(target, new String[]{"getRemovalReason", "m_213780_"});
            default -> {
            }
        }
    }

    private static String name(int path) {
        return switch (path) {
            case 1 -> "NativeRedefineAll";
            case 2 -> "NativeKill";
            case 3 -> "UnsafeWrite";
            case 4 -> "MixinAccessorWrite";
            case 5 -> "StorageExtract";
            case 6 -> "MapSweep";
            case 7 -> "DirectChunkMapRemove";
            case 8 -> "EntityManagerForceRemove";
            case 9 -> "MixinInvokerCall";
            case 10 -> "VanillaSetRemoved";
            case 11 -> "VanillaRemove";
            case 12 -> "VanillaDiscard";
            case 13 -> "ZeroHealthSetRemoved";
            case 14 -> "ZeroHealthDie";
            case 15 -> "MutatorBorrow";
            default -> "ProviderBorrow";
        };
    }

    // ===================== 各路径实现 =====================

    /** ① JVMTI 改方法体（需要 agent 侧提供补丁字节；当前降级）。 */
    private static void nativeRedefineAll(Entity target) {
        try {
            String internalName = target.getClass().getName().replace('.', '/');
            byte[] bytes = NativeBridge.nativeCaptureRuntimeBytes(internalName);
            if (bytes != null && bytes.length > 0 && !internalName.startsWith("net/minecraft/")) {
                NativeBridge.nativeRedefineClass(internalName, bytes);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] NativeRedefineAll 不可用: {}", t.toString());
        }
    }

    /** ④ Mixin accessor 直写 removalReason（走 refmap，生产同样有效）。 */
    private static void mixinAccessorWrite(Entity target) {
        try {
            if (target instanceof com.maha_fish.finaljudgment.mixin.EntityAccessor accessor) {
                accessor.fj$setRemovalReason(Entity.RemovalReason.KILLED);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] MixinAccessorWrite 不可用: {}", t.toString());
        }
    }

    /** ⑨ Mixin invoker 调原版 remove（绕过反射开销与 SRG 名问题）。 */
    private static void mixinInvokerCall(Entity target) {
        try {
            if (target instanceof com.maha_fish.finaljudgment.mixin.EntityInvoker invoker) {
                invoker.fj$invokeRemove(Entity.RemovalReason.KILLED);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] MixinInvokerCall 不可用: {}", t.toString());
        }
    }

    /** ② JNI 直写 removalReason（NativeKill 已补 Java 声明，dll 侧导出早已存在）。 */
    private static void nativeKill(Entity target) {
        com.maha_fish.finaljudgment.nativebridge.NativeKill.kill(target);
    }

    /** ③ Unsafe 直写 removalReason。 */
    private static void unsafeWriteRemoval(Entity target) {
        long offset = FieldWriter.offsetOf(Entity.class,
                new String[]{"removalReason", "f_146795_"}, "RemovalReason");
        if (offset < 0) {
            return;
        }
        FieldWriter.writeObject(target, offset, Entity.RemovalReason.KILLED);
    }

    /** ⑧ 反射调 entityManager.remove。 */
    private static void entityManagerRemove(ServerLevel level, Entity target) {
        try {
            Object manager = null;
            for (Method m : level.getClass().getMethods()) {
                if (m.getParameterCount() == 0
                        && m.getReturnType().getSimpleName().endsWith("PersistentEntitySectionManager")) {
                    manager = m.invoke(level);
                    break;
                }
            }
            if (manager == null) {
                return;
            }
            for (Method m : manager.getClass().getMethods()) {
                if (m.getName().equals("remove") && m.getParameterCount() == 1) {
                    m.invoke(manager, target);
                    return;
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] entityManager.remove 失败: {}", t.toString());
        }
    }

    /** 供 9d 复用的重注入（击杀失败后回滚用）。 */
    public static boolean rollback(ServerLevel level, Entity target) {
        try {
            return EntityReinjector.reattach(level, target);
        } catch (Throwable t) {
            return false;
        }
    }
}
