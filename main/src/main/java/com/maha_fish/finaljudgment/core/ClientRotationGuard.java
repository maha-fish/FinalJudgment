package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端朝向守护（7.9 扩展 11 / 规范 37 的客户端侧）。
 *
 * <p>两 tick 一致性对比 + 位置阈值（{@link Constants#POSITION_TOLERANCE}）：
 * 若服务端回传的位置与本地偏差超阈值，说明被传送/被回拉 → 重置基准，避免把异常朝向用于命中判定。</p>
 */
public final class ClientRotationGuard {

    private static final Map<UUID, Vec3> LAST_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> LAST_YAW = new ConcurrentHashMap<>();
    private static final AtomicInteger RESETS = new AtomicInteger();

    private ClientRotationGuard() {
    }

    /** 每客户端 tick 调用。 */
    public static void tick(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        Vec3 current = player.position();
        Vec3 previous = LAST_POS.put(id, current);
        float yaw = player.getYRot();
        Float previousYaw = LAST_YAW.put(id, yaw);
        if (previous == null || previousYaw == null) {
            return;
        }
        if (previous.distanceTo(current) > Constants.POSITION_TOLERANCE * 4.0D) {
            // 大幅位移：视为被传送，重置基准（不改朝向，只丢历史）
            RESETS.incrementAndGet();
            FinalJudgment.LOGGER.debug("[FJ] 位置跳变，已重置朝向基准: {} delta={}",
                    id, previous.distanceTo(current));
            return;
        }
        if (Math.abs(previousYaw - yaw) > 180.0F) {
            RESETS.incrementAndGet();
        }
    }

    public static void forget(UUID id) {
        LAST_POS.remove(id);
        LAST_YAW.remove(id);
    }

    public static int resets() {
        return RESETS.get();
    }
}
