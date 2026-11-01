package com.maha_fish.finaljudgment.coremod;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 各目标类的注入点（5.2 功能 3）。方法名一律走 SRG 候选（反射自 common），
 * 描述符不是 SRG 名（类名在生产环境保持官方名），可以直接写字面量。
 */
public final class Patches {

    private static final String TICKER = "com/maha_fish/finaljudgment/coremod/CoremodKillTicker";

    private Patches() {
    }

    public static boolean entity(ClassNode node, ClassLoader loader) {
        boolean changed = false;
        changed |= guard(node, loader, "ENTITY_SET_REMOVED",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", "protectedPlayer");
        changed |= guard(node, loader, "ENTITY_REMOVE",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", "protectedPlayer");
        changed |= guard(node, loader, "ENTITY_DISCARD", "()V", "protectedPlayer");
        changed |= tickHook(node);
        return changed;
    }

    public static boolean livingEntity(ClassNode node, ClassLoader loader) {
        boolean changed = false;
        changed |= guard(node, loader, "LIVING_ENTITY_SET_HEALTH", "(F)V", "protectedPlayer");
        changed |= guard(node, loader, "LIVING_ENTITY_HURT",
                "(Lnet/minecraft/world/damagesource/DamageSource;F)Z", "protectedPlayer");
        changed |= guard(node, loader, "LIVING_ENTITY_KILL", "()V", "protectedPlayer");
        changed |= guard(node, loader, "LIVING_ENTITY_DIE",
                "(Lnet/minecraft/world/damagesource/DamageSource;)V", "protectedPlayer");
        return changed;
    }

    public static boolean serverPlayer(ClassNode node, ClassLoader loader) {
        boolean changed = false;
        changed |= guard(node, loader, "LIVING_ENTITY_DIE",
                "(Lnet/minecraft/world/damagesource/DamageSource;)V", "protectedPlayer");
        changed |= guard(node, loader, "PLAYER_DROP_EQUIPMENT", "()V", "protectedPlayer");
        return changed;
    }

    /** 仅当 Forge 提供该方法时注入；找不到打 INFO 跳过，不报错。MCP 名 + SRG 名双候选。 */
    public static boolean serverLevel(ClassNode node) {
        MethodNode method = findMethod(node,
                new String[]{"removeEntityComplete", "m_143429_"},
                "(Lnet/minecraft/world/entity/Entity;Z)V");
        if (method == null) {
            CoremodLog.info("跳过守卫 " + node.name + "：未找到 removeEntityComplete/m_143429_（Forge 未提供，安全跳过）");
            return false;
        }
        boolean injected = GuardEmitter.guardMethod(method, "protectedPlayer");
        if (injected) {
            CoremodLog.info("守卫已加: " + node.name + "." + method.name + method.desc);
        }
        return injected;
    }

    /** Player：dropEquipment() 在这里（ServerPlayer 未覆写），双候选名。 */
    public static boolean player(ClassNode node, ClassLoader loader) {
        return guard(node, loader, "PLAYER_DROP_EQUIPMENT", "()V", "protectedPlayer");
    }

    /** 仅客户端：服务端根本不会加载 Minecraft 类，所以列在 targets 里也是安全的。 */
    public static boolean minecraftRunTick(ClassNode node) {
        MethodNode method = findMethodByLiteralName(node, "runTick", "(Z)V");
        if (method == null) {
            CoremodLog.info("跳过 " + node.name + ".runTick：方法不存在");
            return false;
        }
        if (alreadyCalls(method, TICKER, "clientTick")) {
            return false;
        }
        InsnList hook = new InsnList();
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TICKER, "clientTick", "()V", false));
        method.instructions.insert(hook);
        return true;
    }

    private static boolean guard(ClassNode node, ClassLoader loader, String srgKey, String desc, String killFlagField) {
        String[] candidates = SrgBridge.candidates(loader, srgKey);
        if (candidates == null) {
            CoremodLog.info("跳过守卫 " + node.name + " " + srgKey + "：SRG 候选不可用");
            return false;
        }
        MethodNode method = findMethod(node, candidates, desc);
        if (method == null) {
            CoremodLog.info("跳过守卫 " + node.name + " " + srgKey + "：方法不存在 desc=" + desc);
            return false;
        }
        boolean injected = GuardEmitter.guardMethod(method, killFlagField);
        if (injected) {
            // 逐方法日志：出问题时能一眼看出是哪个方法被加了守卫
            CoremodLog.info("守卫已加: " + node.name + "." + method.name + desc + " (字段=" + killFlagField + ")");
        }
        return injected;
    }

    private static MethodNode findMethod(ClassNode node, String[] names, String desc) {
        for (MethodNode method : node.methods) {
            if (method == null || method.name == null || method.desc == null) {
                continue;
            }
            if (!desc.equals(method.desc)) {
                continue;
            }
            for (String name : names) {
                if (name != null && name.equals(method.name)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static MethodNode findMethodByLiteralName(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method != null && name.equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    /** Entity.tick 入口：调 CoremodKillTicker.tick(Object)（异常在 Java 侧兜住）。 */
    private static boolean tickHook(ClassNode node) {
        // 独立开关：每实体每 tick 的反射，实测会拖慢服务端（表现为打不到生物）
        if (!Boolean.getBoolean("fj.coremod.patch.tickhook")) {
            return false;
        }
        MethodNode tick = null;
        String[] candidates = null;
        try {
            candidates = SrgBridge.candidates(Thread.currentThread().getContextClassLoader(), "ENTITY_TICK");
        } catch (Throwable ignored) {
            // 下面按 null 处理
        }
        if (candidates == null) {
            CoremodLog.info("跳过 " + node.name + ".tick 注入：SRG 候选不可用");
            return false;
        }
        for (MethodNode method : node.methods) {
            if (method != null && "()V".equals(method.desc)) {
                for (String name : candidates) {
                    if (name != null && name.equals(method.name)) {
                        tick = method;
                        break;
                    }
                }
            }
            if (tick != null) {
                break;
            }
        }
        if (tick == null) {
            CoremodLog.info("跳过 " + node.name + ".tick 注入：方法不存在");
            return false;
        }
        if (alreadyCalls(tick, TICKER, "tick")) {
            return false;
        }
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TICKER, "tick", "(Ljava/lang/Object;)V", false));
        tick.instructions.insert(hook);
        return true;
    }

    private static boolean alreadyCalls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) node;
                if (owner.equals(call.owner) && name.equals(call.name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
