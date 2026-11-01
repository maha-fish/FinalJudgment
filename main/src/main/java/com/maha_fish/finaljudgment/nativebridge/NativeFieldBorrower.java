package com.maha_fish.finaljudgment.nativebridge;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 借用对方的 setter/getter（7.5 功能 4 的路径 15/16，弱兜底）。
 *
 * <p>只做"能借就借"，任何失败都返回 false，绝不抛。方法/字段按名字候选（MCP+SRG 双写）查找。</p>
 */
public final class NativeFieldBorrower {

    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    private NativeFieldBorrower() {
    }

    /** 调对方 setter：setXxx(value)。 */
    public static boolean borrowSetter(Object target, String[] names, Object value) {
        if (target == null || names == null) {
            return false;
        }
        for (String name : names) {
            try {
                for (Method method : target.getClass().getMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 1
                            && (value == null || method.getParameterTypes()[0].isInstance(value))) {
                        method.invoke(target, value);
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        return false;
    }

    /** 调对方 getter：getXxx()。 */
    public static Object borrowGetter(Object target, String[] names) {
        if (target == null || names == null) {
            return null;
        }
        for (String name : names) {
            try {
                for (Method method : target.getClass().getMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 0) {
                        return method.invoke(target);
                    }
                }
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    /** 直接写字段（setter 都没有时的最后手段）。 */
    public static boolean borrowField(Object target, String[] names, Object value) {
        if (target == null || names == null) {
            return false;
        }
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                long offset = com.maha_fish.finaljudgment.core.FieldWriter.offsetOf(
                        target.getClass(), new String[]{name}, null);
                if (offset >= 0) {
                    return com.maha_fish.finaljudgment.core.FieldWriter.writeObject(target, offset, value);
                }
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        FinalJudgment.LOGGER.debug("[FJ] borrowField 全部失败: {}", target.getClass().getName());
        return false;
    }
}
