package com.maha_fish.finaljudgment.coremod;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 守卫发射器（5.2 功能 3 / 规范 24）。
 *
 * <p>注入骨架（<b>零 INVOKE</b>，对方 hook 任何 Java 方法都无效）：</p>
 * <pre>
 * L_start:  ALOAD 0
 *           GETSTATIC KillFlag.&lt;field&gt; : Ljava/lang/Object;
 *           IF_ACMPNE L_continue
 *           &lt;按返回类型的默认 return&gt;
 * L_continue: &lt;原指令…&gt;
 * handler:  POP
 *           &lt;按返回类型的默认 return&gt;
 * </pre>
 * try-catch 覆盖整段守卫（Throwable），异常也走默认 return。
 */
public final class GuardEmitter {

    /**
     * 守卫引用的开关类：**必须是 coremod 包**（game 层可见）。
     * 指向 common 会导致 game 层解析失败（已实测），构建期由 verifyCoremodPurity 门禁拦截。
     */
    public static final String KILL_FLAG = "com/maha_fish/finaljudgment/coremod/KillFlag";

    private GuardEmitter() {
    }

    /** 返回 true 表示注入了（false = 已注入过或不可注入）。 */
    public static boolean guardMethod(MethodNode method, String killFlagField) {
        if (method == null || method.name == null || method.name.startsWith("<")) {
            return false;
        }
        if (alreadyGuarded(method, killFlagField)) {
            return false;
        }
        Type returnType = Type.getReturnType(method.desc);

        LabelNode start = new LabelNode();
        LabelNode cont = new LabelNode();
        LabelNode handler = new LabelNode();

        InsnList guard = new InsnList();
        guard.add(start);
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(Opcodes.GETSTATIC, KILL_FLAG, killFlagField, "Ljava/lang/Object;"));
        guard.add(new JumpInsnNode(Opcodes.IF_ACMPNE, cont));
        guard.add(defaultValue(returnType));
        // 关键：我们自己插入的跳转目标必须有 StackMapTable 帧，否则类校验失败/崩溃
        guard.add(cont);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(guard);

        InsnList handlerCode = new InsnList();
        // 防护 1：链接错误必须"响"（ATHROW），不能像 Throwable 那样被安全网吞成静默行为异常
        LabelNode handlerLinkage = new LabelNode();
        handlerCode.add(handlerLinkage);
        handlerCode.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1,
                new Object[]{"java/lang/LinkageError"}));
        // 处理器入口时异常已在栈顶：直接 ATHROW（先 POP 会栈下溢 → VerifyError）
        handlerCode.add(new InsnNode(Opcodes.ATHROW));
        handlerCode.add(handler);
        handlerCode.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1,
                new Object[]{"java/lang/Throwable"}));
        handlerCode.add(new InsnNode(Opcodes.POP));   // 丢掉 Throwable
        handlerCode.add(defaultValue(returnType));
        method.instructions.add(handlerCode);

        // 顺序有意义：LinkageError 条目必须在 Throwable 之前
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, cont, handlerLinkage, "java/lang/LinkageError"));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, cont, handler, "java/lang/Throwable"));
        method.maxStack = Math.max(method.maxStack, 4);
        // 防护 2（运行期可观测）：注入目标的 owner 必须是 coremod 包
        CoremodLog.info("守卫 owner=" + KILL_FLAG);
        return true;
    }

    /** 按返回类型生成默认值 + 对应 return（规范 24）。 */
    public static InsnList defaultValue(Type returnType) {
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

    private static boolean alreadyGuarded(MethodNode method, String killFlagField) {
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) node;
                if (field.getOpcode() == Opcodes.GETSTATIC && KILL_FLAG.equals(field.owner)
                        && killFlagField.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
