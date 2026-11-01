package com.maha_fish.finaljudgment.nativebridge;

import com.maha_fish.finaljudgment.FinalJudgment;

/**
 * Native 直杀（6.2 功能 7）。
 *
 * <p>C 侧 {@code fj_kill.c} 早已实现，但 JNI 符号名对应的是
 * {@code com.maha_fish.finaljudgment.nativebridge.NativeKill.nativeKillEntity}——
 * 本类就是补上这个 Java 侧声明（此前缺失，导致击杀链路径 2 一直降级）。</p>
 *
 * <p>字段候选名由 Java 传入（ENV_SPEC：C 侧禁止硬编码 SRG 名）。</p>
 */
public final class NativeKill {

    /** removalReason 的 MCP / SRG 双候选。 */
    private static final String[] FIELD_CANDIDATES = {"removalReason", "f_146795_"};
    /** 字段描述符（枚举类型）。 */
    private static final String FIELD_DESC = "Lnet/minecraft/world/entity/Entity$RemovalReason;";
    /** RemovalReason.KILLED 的序号（0 = KILLED）。 */
    private static final int REASON_KILLED = 0;

    private NativeKill() {
    }

    public static native boolean nativeKillEntity(Object entity, String[] candidates,
                                                  String fieldDesc, int reasonOrdinal);

    /** 直杀一个实体；Native 未就绪或失败时返回 false（调用方继续下一条路径）。 */
    public static boolean kill(Object entity) {
        if (entity == null || !NativeBridge.isLoaded()) {
            return false;
        }
        try {
            return nativeKillEntity(entity, FIELD_CANDIDATES, FIELD_DESC, REASON_KILLED);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] NativeKill 失败: {}", t.toString());
            return false;
        }
    }
}
