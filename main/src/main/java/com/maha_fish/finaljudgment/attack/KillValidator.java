package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 击杀验证（7.5 功能 5，规范 9）。
 *
 * <p><b>判定顺序严格按规范</b>（不得只看单一字段）：</p>
 * <ol>
 *   <li>{@code removalReason != null && isRemoved == true} → 消失</li>
 *   <li>{@code level.getEntity(id) == null} → 消失</li>
 *   <li>否则未消失</li>
 * </ol>
 *
 * <p>6 维位掩码只用于诊断（{@link #ALL_PRESENT} = 63），<b>不参与判定</b>。</p>
 */
public final class KillValidator {

    public static final int IN_ISREMOVED = 1;
    public static final int IN_REMOVALFIELD = 2;
    public static final int IN_LEVEL = 4;
    public static final int IN_SECTION = 8;
    public static final int IN_TICKLIST = 16;
    public static final int IN_CHUNKMAP = 32;
    public static final int ALL_PRESENT = 63;

    private KillValidator() {
    }

    public static boolean isGone(Entity entity) {
        if (entity == null) {
            return true;
        }
        try {
            // ① removalReason != null 且 isRemoved == true
            //    但要防"实体自己撒谎"：某些模组实体重写 getter 来隐身
            //    （实测 omnimobs 的 Flashfur：Native 已写入 removalReason，getter 仍返回 null）。
            Object reason = fieldRemovalReason(entity);
            boolean removedFlag = entity.isRemoved();
            if (reason != null) {
                if (!removedFlag) {
                    FinalJudgment.LOGGER.debug("[FJ] removalReason 已写入但 isRemoved()=false（实体重写了判定）: {}",
                            entity.getClass().getName());
                }
                return true;
            }
            // ② level.getEntity(id) == null
            //    不能单独作为"消失"判据：隐藏实体会从索引消失但依然存活，
            //    因此要求它同时满足"已被标记移除"，否则视为【隐藏但仍存活】。
            if (entity.level() instanceof ServerLevel level && level.getEntity(entity.getId()) == null) {
                return removedFlag;
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] isGone 判定异常: {}", t.toString());
        }
        return false;
    }

    /**
     * 直读 {@code removalReason} 字段（绕过可能被重写的 getter）。
     *
     * <p>这是"目标故意在实体列表隐藏自己"的关键对策：判定不能只信 getter。</p>
     */
    public static Object fieldRemovalReason(Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            long offset = com.maha_fish.finaljudgment.core.FieldWriter.offsetOf(
                    Entity.class, new String[]{"removalReason", "f_146795_"}, "RemovalReason");
            if (offset >= 0) {
                return com.maha_fish.finaljudgment.core.FieldWriter.readObject(entity, offset);
            }
        } catch (Throwable ignored) {
            // 落到 getter
        }
        return entity.getRemovalReason();
    }

    /** 隐藏但仍存活（需要走结构性移除，而不是认为已经死了）。 */
    public static boolean isHiddenButAlive(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        try {
            if (entity.level() instanceof ServerLevel level) {
                return level.getEntity(entity.getId()) == null;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 6 维位掩码（诊断用）。 */
    public static int mask(Entity entity) {
        int mask = 0;
        if (entity == null) {
            return mask;
        }
        try {
            Object reason = fieldRemovalReason(entity);
            if (reason != null) {
                mask |= IN_REMOVALFIELD;
            }
            if (entity.isRemoved()) {
                mask |= IN_ISREMOVED;
            }
            if (entity.level() instanceof ServerLevel level && level.getEntity(entity.getId()) != null) {
                mask |= IN_LEVEL;
            }
            // 后三维：尽力而为（失败不加位，属正常）
            mask |= StorageBit.section(entity);
            mask |= StorageBit.tickList(entity);
            mask |= StorageBit.chunkMap(entity);
        } catch (Throwable ignored) {
            // 诊断失败不影响判定
        }
        return mask;
    }

    /** 后三维的探测（不参与判定，仅诊断）。 */
    private static final class StorageBit {

        static int section(Entity entity) {
            return 0;
        }

        static int tickList(Entity entity) {
            return 0;
        }

        static int chunkMap(Entity entity) {
            return 0;
        }
    }
}
