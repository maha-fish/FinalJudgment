package com.maha_fish.finaljudgment.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LivingEntity 方法体重写（7.4.3 功能 3）。
 *
 * <p>守卫骨架（注入到方法入口，保留原指令）：</p>
 * <pre>
 * ALOAD 0; GETSTATIC KillFlag.protectedPlayer; IF_ACMPNE Lcont;
 * &lt;默认值&gt;; &lt;对应 return&gt;
 * Lcont: F_SAME            ← 必须有帧，否则 VerifyError
 * </pre>
 * <p>幂等：已存在该 GETSTATIC 就跳过。owner 必须是 coremod 包（game 层可见）。</p>
 */
public final class MainTransformer implements MainClassTransformer.Patch {

    public static final String TARGET = "net/minecraft/world/entity/LivingEntity";
    private static final String KILL_FLAG = "com/maha_fish/finaljudgment/coremod/KillFlag";
    private static final String FIELD = "protectedPlayer";
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    @Override
    public boolean patch(ClassLoader loader, String internalName, ClassNode classNode) {
        if (!TARGET.equals(internalName)) {
            return false;
        }
        // 诊断探针（不依赖日志通道，主 mod 直接读属性）
        System.setProperty("fj.agent.patch.hits",
                Integer.toString(Integer.getInteger("fj.agent.patch.hits", 0) + 1));
        System.setProperty("fj.agent.patch.last", String.valueOf(internalName));
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (!isGuardTarget(method) || alreadyGuarded(method)) {
                continue;
            }
            Type returnType = Type.getReturnType(method.desc);
            LabelNode cont = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new FieldInsnNode(Opcodes.GETSTATIC, KILL_FLAG, FIELD, "Ljava/lang/Object;"));
            guard.add(new JumpInsnNode(Opcodes.IF_ACMPNE, cont));
            guard.add(defaultValue(returnType));
            guard.add(cont);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(guard);
            method.maxStack = Math.max(method.maxStack, 3);
            changed = true;
        }
        if (changed && LOGGED.compareAndSet(false, true)) {
            AgentLog.info("MainTransformer: LivingEntity 守卫已注入（owner=" + KILL_FLAG + "）");
        }
        return changed;
    }

    /** 只对 4 个关键方法：setHealth(F)V / hurt(DS,F)Z / kill()V / die(DS)V。 */
    private static boolean isGuardTarget(MethodNode method) {
        if (method.name == null || method.name.startsWith("<")) {
            return false;
        }
        String desc = method.desc;
        return desc.equals("(F)V")
                || desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")
                || desc.equals("()V")
                || desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;)V");
    }

    private static InsnList defaultValue(Type returnType) {
        InsnList list = new InsnList();
        switch (returnType.getSort()) {
            case Type.VOID:
                list.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                list.add(new InsnNode(Opcodes.ICONST_0));
                list.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.LONG:
                list.add(new InsnNode(Opcodes.LCONST_0));
                list.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.FLOAT:
                list.add(new InsnNode(Opcodes.FCONST_0));
                list.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.DOUBLE:
                list.add(new InsnNode(Opcodes.DCONST_0));
                list.add(new InsnNode(Opcodes.DRETURN));
                break;
            default:
                list.add(new InsnNode(Opcodes.ACONST_NULL));
                list.add(new InsnNode(Opcodes.ARETURN));
                break;
        }
        return list;
    }

    private static boolean alreadyGuarded(MethodNode method) {
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) node;
                if (field.getOpcode() == Opcodes.GETSTATIC
                        && KILL_FLAG.equals(field.owner) && FIELD.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
