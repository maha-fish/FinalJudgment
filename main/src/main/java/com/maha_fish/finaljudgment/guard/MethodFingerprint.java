package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 方法指纹（对应示例项目的 {@code MethodFingerprint}）。
 *
 * <p>作用：在 {@link MethodRedirect} 改写别人的方法之前，先确认"我们要改的确实是那个方法"。
 * 同名方法在继承链/重载里很容易误伤，指纹基于 <b>方法名 + 描述符 + 修饰符 + 返回类型</b>，
 * 只有完全匹配才允许改写。</p>
 */
public final class MethodFingerprint {

    private static final AtomicInteger CHECKED = new AtomicInteger();
    private static final AtomicInteger REJECTED = new AtomicInteger();

    private MethodFingerprint() {
    }

    /** 生成指纹字符串，例如 {@code isAlive()Z|public|native=false|abstract=false} */
    /**
     * 方法体指纹（扩展 5/6-2）：对类的每个方法体计算 SHA-256。
     *
     * <p>规范要求的规范化处理：</p>
     * <ul>
     *   <li>忽略 {@code LabelNode / LineNumberNode / FrameNode}</li>
     *   <li>{@code LDC} 常量序列化（类型 + 值）</li>
     *   <li>{@code InvokeDynamic} 序列化 bsm + name + desc</li>
     *   <li>跳转标签用"首次出现 ID"代替绝对偏移（跨 JVM 一致）</li>
     * </ul>
     *
     * @return 方法签名 → 方法体哈希（顺序稳定）
     */
    public static java.util.Map<String, String> methodHashes(byte[] classBytes) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (classBytes == null) {
            return result;
        }
        try {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(classBytes);
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            reader.accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG);
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                StringBuilder sb = new StringBuilder();
                java.util.Map<org.objectweb.asm.tree.LabelNode, Integer> labelIds = new java.util.IdentityHashMap<>();
                for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst();
                     insn != null; insn = insn.getNext()) {
                    switch (insn.getType()) {
                        case org.objectweb.asm.tree.AbstractInsnNode.LABEL: {
                            Integer id = labelIds.get(insn);
                            if (id == null) {
                                id = labelIds.size();
                                labelIds.put((org.objectweb.asm.tree.LabelNode) insn, id);
                            }
                            sb.append("LABEL:").append(id).append(';');
                            break;
                        }
                        case org.objectweb.asm.tree.AbstractInsnNode.LINE:
                        case org.objectweb.asm.tree.AbstractInsnNode.FRAME:
                            break;                        // 忽略
                        case org.objectweb.asm.tree.AbstractInsnNode.LDC_INSN: {
                            Object value = ((org.objectweb.asm.tree.LdcInsnNode) insn).cst;
                            sb.append("LDC:").append(value == null ? "null"
                                    : value.getClass().getSimpleName() + "=" + value).append(';');
                            break;
                        }
                        case org.objectweb.asm.tree.AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
                            org.objectweb.asm.tree.InvokeDynamicInsnNode indy =
                                    (org.objectweb.asm.tree.InvokeDynamicInsnNode) insn;
                            sb.append("INDY:").append(indy.bsm == null ? "-" : indy.bsm.getOwner())
                                    .append('#').append(indy.bsm == null ? "-" : indy.bsm.getName())
                                    .append('#').append(indy.name).append(indy.desc).append(';');
                            break;
                        }
                        default:
                            sb.append(insn.getOpcode()).append(';');
                            break;
                    }
                }
                result.put(method.name + method.desc, sha256(sb.toString()));
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] methodHashes 失败: {}", t.toString());
        }
        return result;
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(text.hashCode());
        }
    }

    public static String fingerprint(Method method) {
        if (method == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getSimpleName());
        }
        sb.append(")").append(method.getReturnType().getSimpleName());
        sb.append("|").append(Modifier.toString(method.getModifiers()));
        return sb.toString();
    }

    /**
     * 校验目标类里是否<b>恰好有一个</b>符合 {@code name + descriptor} 的方法。
     *
     * @param target     目标类
     * @param methodName 方法名
     * @param descriptor JVM 描述符（如 {@code ()Z}）
     * @return true = 唯一匹配，可安全改写
     */
    public static boolean isUnique(Class<?> target, String methodName, String descriptor) {
        if (target == null || methodName == null) {
            return false;
        }
        CHECKED.incrementAndGet();
        int matches = 0;
        for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (descriptor != null && !descriptor.equals(descriptorOf(method))) {
                    continue;
                }
                matches++;
            }
        }
        if (matches == 1) {
            return true;
        }
        REJECTED.incrementAndGet();
        FinalJudgment.LOGGER.warn("[FJ] 方法指纹校验未通过: {}#{}{}（匹配 {} 个）",
                target.getName(), methodName, descriptor, matches);
        return false;
    }

    /** 列出目标类（含继承链）所有返回 boolean 的无参方法，用于诊断"它可以怎么防"。 */
    public static List<String> booleanMethods(Class<?> target) {
        List<String> result = new ArrayList<>();
        if (target == null) {
            return result;
        }
        for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getReturnType() == boolean.class && method.getParameterCount() == 0) {
                    result.add(c.getSimpleName() + "." + fingerprint(method));
                }
            }
        }
        return result;
    }

    /** 生成 JVM 描述符，例如 {@code ()Z}。 */
    public static String descriptorOf(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> param : method.getParameterTypes()) {
            sb.append(descriptorOf(param));
        }
        sb.append(")").append(descriptorOf(method.getReturnType()));
        return sb.toString();
    }

    private static String descriptorOf(Class<?> type) {
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        if (type == double.class) {
            return "D";
        }
        if (type == void.class) {
            return "V";
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    public static int checked() {
        return CHECKED.get();
    }

    public static int rejected() {
        return REJECTED.get();
    }
}
