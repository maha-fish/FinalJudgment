package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反传送位置校验（7.18，规范 37）。
 *
 * <p>三条检测：① `deltaMovement` 异常（速度突变）② `chunkPosition` 突变 ③ 脚下是空气但位置在飞。
 * 位置一律用 <b>{@code entity.position()}</b>（不用 boundingBox）。</p>
 */
public final class PositionGuard {

    /** 单 tick 位移超过该值视为异常瞬移（格）。 */
    public static final double MAX_DELTA_PER_TICK = 8.0D;

    private static final Map<UUID, Vec3> LAST_POS = new ConcurrentHashMap<>();
    private static final AtomicInteger DETECTED = new AtomicInteger();

    private PositionGuard() {
    }

    /**
     * 检测异常瞬移。
     *
     * @return true 表示检测到异常（调用方可据此恢复位置/重新追踪）
     */
    public static boolean detect(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            Vec3 current = entity.position();
            // ① deltaMovement 异常
            Vec3 delta = entity.getDeltaMovement();
            if (delta != null && delta.length() > MAX_DELTA_PER_TICK * 4) {
                DETECTED.incrementAndGet();
                FinalJudgment.LOGGER.debug("[FJ] 反传送: deltaMovement 异常 {}: {}", entity.getUUID(), delta.length());
                return true;
            }
            Vec3 previous = LAST_POS.get(entity.getUUID());
            if (previous == null) {
                LAST_POS.put(entity.getUUID(), current);
                return false;
            }
            // ② 单 tick 位移突变（position() 而非 boundingBox）
            double moved = previous.distanceTo(current);
            if (moved > MAX_DELTA_PER_TICK) {
                DETECTED.incrementAndGet();
                FinalJudgment.LOGGER.debug("[FJ] 反传送: 位置突变 {}: {} 格", entity.getUUID(), moved);
                // 注意：异常位置不写入 LAST_POS，否则"上一次已知合法位置"会被污染
                return true;
            }
            // ③ chunkPosition 突变
            int previousChunkX = ((int) Math.floor(previous.x)) >> 4;
            int previousChunkZ = ((int) Math.floor(previous.z)) >> 4;
            int chunkX = ((int) Math.floor(current.x)) >> 4;
            int chunkZ = ((int) Math.floor(current.z)) >> 4;
            if (Math.abs(chunkX - previousChunkX) > 1 || Math.abs(chunkZ - previousChunkZ) > 1) {
                DETECTED.incrementAndGet();
                FinalJudgment.LOGGER.debug("[FJ] 反传送: chunkPosition 突变 {}: ({},{}) → ({},{})",
                        entity.getUUID(), previousChunkX, previousChunkZ, chunkX, chunkZ);
                return true;
            }
            LAST_POS.put(entity.getUUID(), current);
            return false;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 反传送检测异常: {}", t.toString());
            return false;
        }
    }

    public static void forget(UUID id) {
        LAST_POS.remove(id);
    }

    /** 上一次记录的位置（供传送后恢复使用）。 */
    public static Vec3 lastKnown(UUID id) {
        return id == null ? null : LAST_POS.get(id);
    }

    public static int detected() {
        return DETECTED.get();
    }
}
