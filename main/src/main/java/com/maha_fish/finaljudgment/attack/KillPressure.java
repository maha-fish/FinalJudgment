package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.FieldWriter;
import com.maha_fish.finaljudgment.core.StorageExtract;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压力窗口（7.5 功能 6）。
 *
 * <p>状态机：{@code activate → ACTIVE}；每 tick {@code isGone} 则 {@code stableTicks++}，
 * 达到 {@link #STABLE_TICKS}=5 → {@code DONE}；否则 {@code rePush()} 且 {@code pushedTicks++}，
 * 达到 {@link #PRESSURE_TICKS}=60 → {@code GIVE_UP}（并还原血量）。</p>
 */
public final class KillPressure {

    public static final int STABLE_TICKS = 5;
    public static final int PRESSURE_TICKS = 60;

    public enum State { IDLE, ACTIVE, DONE, GIVE_UP }

    private static volatile State state = State.IDLE;
    private static volatile ServerLevel level;
    private static volatile Entity target;
    private static int stableTicks;
    private static int pushedTicks;
    private static final AtomicInteger DONE_COUNT = new AtomicInteger();
    private static final AtomicInteger GIVEUP_COUNT = new AtomicInteger();

    private KillPressure() {
    }

    public static synchronized void activate(ServerLevel serverLevel, Entity entity) {
        level = serverLevel;
        target = entity;
        stableTicks = 0;
        pushedTicks = 0;
        state = State.ACTIVE;
    }

    public static void tick() {
        if (state != State.ACTIVE) {
            return;
        }
        Entity entity = target;
        ServerLevel serverLevel = level;
        if (entity == null || serverLevel == null) {
            state = State.IDLE;
            return;
        }
        try {
            if (KillValidator.isGone(entity)) {
                // 已"判定消失"仍继续重压 5 tick：对抗把实体重新挂回 section 的模组
                // （super-steve 的 SEntityCallback.onRemove / SSChunkMap.removeEntity）
                EntityRemover.remove(serverLevel, entity);
                stableTicks++;
                if (stableTicks >= STABLE_TICKS) {
                    state = State.DONE;
                    DONE_COUNT.incrementAndGet();
                    FinalJudgment.LOGGER.info("[FJ] 压力窗口 DONE（连续 {} tick 稳定）", stableTicks);
                }
                return;
            }
            rePush(serverLevel, entity);
            pushedTicks++;
            if (pushedTicks >= PRESSURE_TICKS) {
                state = State.GIVE_UP;
                GIVEUP_COUNT.incrementAndGet();
                restoreHealth(entity);
                // 放弃前也要广播移除：否则客户端会留下"冻结幽灵"（碰撞箱在、服务端查不到）
                KillPackets.broadcastRemove(serverLevel, entity);
                FinalJudgment.LOGGER.warn("[FJ] 压力窗口 GIVE_UP（{} tick 未消失，已还原血量 + 广播移除）", pushedTicks);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 压力窗口异常: {}", t.toString());
        }
    }

    /** rePush 的 4 项操作（NativeKill / UnsafeWrite / StorageExtract / MixinInvokerCall）。 */
    private static void rePush(ServerLevel serverLevel, Entity entity) {
        try {
            long offset = FieldWriter.offsetOf(Entity.class,
                    new String[]{"removalReason", "f_146795_"}, "RemovalReason");
            if (offset >= 0) {
                FieldWriter.writeObject(entity, offset, Entity.RemovalReason.DISCARDED);
            }
        } catch (Throwable ignored) {
            // 继续下一项
        }
        try {
            StorageExtract.extract(serverLevel, entity);
        } catch (Throwable ignored) {
            // 继续下一项
        }
        // NativeKill 与 MixinInvokerCall 未就绪时静默跳过（11b / Native 导出后自动生效）
    }

    private static void restoreHealth(Entity entity) {
        try {
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                living.setHealth(Math.max(1.0F, living.getMaxHealth()));
            }
        } catch (Throwable ignored) {
            // 还原失败不致命
        }
    }

    public static State state() {
        return state;
    }

    public static int doneCount() {
        return DONE_COUNT.get();
    }

    public static int giveUpCount() {
        return GIVEUP_COUNT.get();
    }
}
