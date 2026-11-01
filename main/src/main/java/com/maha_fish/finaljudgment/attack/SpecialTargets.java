package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.guard.MethodRedirect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 免疫体系识别与对策（规则化，不针对具体实体）。
 *
 * <p><b>背景</b>：外部模组常见用"标记接口 / 注解 + 检查器"实现免疫，例如 omnimobs 的
 * {@code IDataCommandImmune} 标记接口配合 {@code DataCommandsMixin} 拒绝任何外部改动。
 * 因此对策不应硬编码实体 ID，而应识别这类体系并统一处理。</p>
 *
 * <p><b>识别规则</b>（简单名包含任一关键字，且是接口或注解）：</p>
 * <pre>Immune / Invulnerable / Protected / Immortal / Unkillable / Undying</pre>
 *
 * <p><b>对策</b>（按顺序）：</p>
 * <ol>
 *   <li>把目标类的 {@code isAlive/isRemoved/isInvisible} 用 JVMTI 钉死</li>
 *   <li>对目标实现的每个"免疫型接口"：把其中返回 boolean 的无参方法改写为 {@code false}
 *       —— 让所有检查器（Mixin/事件处理器）判定"它并不免疫"</li>
 *   <li>目标类上标注的"免疫型注解"：同样记录并在 {@link #describe} 中列出，便于定位</li>
 * </ol>
 */
public final class SpecialTargets {

    private static final String[] IMMUNE_KEYWORDS = {
            "immune", "invulnerable", "protected", "immortal", "unkillable", "undying"
    };

    private SpecialTargets() {
    }

    /** 该类是否携带"免疫体系"（接口 / 注解 / 自身方法名）。 */
    public static boolean matches(net.minecraft.world.entity.Entity entity) {
        return entity != null && matches(entity.getClass());
    }

    public static boolean matches(Class<?> type) {
        if (type == null) {
            return false;
        }
        // 类级扫描（扩展 4-1）标记的可疑防御类，一并走特化
        try {
            if (com.maha_fish.finaljudgment.guard.DefenseScanner.isSuspect(type)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            for (Class<?> itf : allInterfaces(type)) {
                if (looksImmune(itf.getSimpleName())) {
                    return true;
                }
            }
            for (Annotation annotation : type.getAnnotations()) {
                if (looksImmune(annotation.annotationType().getSimpleName())) {
                    return true;
                }
            }
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.getReturnType() == boolean.class && method.getParameterCount() == 0
                            && looksImmune(method.getName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 执行对策，返回成功改写的点数（0 表示没发现可改写的点）。 */
    public static int enhance(net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return 0;
        }
        return enhance(entity.getClass());
    }

    public static int enhance(Class<?> type) {
        if (type == null) {
            return 0;
        }
        int count = 0;
        FinalJudgment.LOGGER.info("[FJ] 免疫体系命中: {} | {}", type.getName(), describe(type));

        // ① 存活三方法
        count += MethodRedirect.neutralizelivenessForced(type);

        // ①.5 扩展 5/6-3：把关键覆写改成直调父类（让它们的防御逻辑失效）
        count += rewriteOverridesToSuper(type);

        // ② 免疫型接口里的布尔方法 → false
        for (Class<?> itf : allInterfaces(type)) {
            if (!looksImmune(itf.getSimpleName())) {
                continue;
            }
            count += neutralizeBooleanMethods(itf);
            // 实体自身对该接口的实现也一并处理
            count += neutralizeBooleanMethods(type);
        }

        // ③ 实体自身名字含免疫关键字的布尔方法
        count += neutralizeBooleanMethods(type);

        if (count > 0) {
            FinalJudgment.LOGGER.info("[FJ] 免疫对策已应用: {} 个方法被改写（{}）", count, type.getSimpleName());
        }
        return count;
    }

    /** 把类里"名字含免疫关键字"的 boolean 无参方法改写为 false。 */
    private static int neutralizeBooleanMethods(Class<?> type) {
        int count = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                try {
                    if (method.getReturnType() != boolean.class || method.getParameterCount() != 0) {
                        continue;
                    }
                    if (!looksImmune(method.getName())) {
                        continue;
                    }
                    String key = c.getName() + "#" + method.getName();
                    if (!seen.add(key)) {
                        continue;
                    }
                    if (MethodRedirect.forceBooleanReturnInternalPublic(c, method.getName(), "()Z", false)) {
                        count++;
                    }
                } catch (Throwable ignored) {
                    // 单个失败不影响其他
                }
            }
        }
        return count;
    }

    /** 诊断用：列出该类的免疫体系构成。 */
    public static String describe(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> itf : allInterfaces(type)) {
            if (looksImmune(itf.getSimpleName())) {
                sb.append("interface=").append(itf.getSimpleName()).append(' ');
            }
        }
        for (Annotation annotation : type.getAnnotations()) {
            if (looksImmune(annotation.annotationType().getSimpleName())) {
                sb.append("annotation=@").append(annotation.annotationType().getSimpleName()).append(' ');
            }
        }
        return sb.length() == 0 ? "(仅方法级)" : sb.toString().trim();
    }

    private static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> result = new LinkedHashSet<>();
        try {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Class<?> itf : c.getInterfaces()) {
                    result.add(itf);
                    result.addAll(java.util.List.of(itf.getInterfaces()));
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return result;
    }

    private static boolean looksImmune(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String keyword : IMMUNE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    /** 关键覆写方法（签名固定）。 */
    private static final String[][] OVERRIDES = {
            {"isAlive", "()Z"},
            {"isRemoved", "()Z"},
            {"isInvisible", "()Z"},
            {"isInvulnerableTo", "(Lnet/minecraft/world/damagesource/DamageSource;)Z"},
            {"hurt", "(Lnet/minecraft/world/damagesource/DamageSource;F)Z"},
            {"die", "(Lnet/minecraft/world/damagesource/DamageSource;)V"}
    };

    /** 把目标类里对关键方法的覆写改成"直接调 super"（扩展 5/6-3）。 */
    private static int rewriteOverridesToSuper(Class<?> type) {
        int count = 0;
        for (String[] spec : OVERRIDES) {
            try {
                // 只有在"本类声明了该方法"时才需要改回 super
                type.getDeclaredMethod(spec[0]);
                if (com.maha_fish.finaljudgment.guard.MethodRedirect.rewriteToSuper(type, spec[0], spec[1])) {
                    count++;
                }
            } catch (NoSuchMethodException ignored) {
                // 本类没覆写，跳过
            } catch (Throwable t) {
                // 单个失败不影响其他
            }
        }
        return count;
    }
}