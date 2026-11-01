package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 击杀链的收尾包（Unit 12 联调修复）。
 *
 * <p><b>为什么必需</b>：击杀链会用 StorageExtract 把实体从 ChunkMap / entityManager / tickList
 * 等 6 处索引里摘掉。一旦摘掉，服务端就<b>不再认为这个实体还被追踪</b>，于是也<b>不会</b>发
 * {@code ClientboundRemoveEntitiesPacket}；客户端因此保留一个"冻结的幽灵实体"（碰撞箱还在、
 * 但服务端工具查不到，重进世界后消失）。所以必须在摘除前/后<b>主动广播移除包</b>。</p>
 */
public final class KillPackets {

    private static final double BROADCAST_RANGE_SQR = 512.0D * 512.0D;
    private static final AtomicInteger SENT = new AtomicInteger();

    private KillPackets() {
    }

    /** 主动让所有能看到它的客户端移除该实体。 */
    public static void broadcastRemove(ServerLevel level, Entity target) {
        if (level == null || target == null) {
            return;
        }
        try {
            ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(target.getId());
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(target) <= BROADCAST_RANGE_SQR) {
                    player.connection.send(packet);
                    SENT.incrementAndGet();
                }
            }
            FinalJudgment.LOGGER.debug("[FJ] 已广播移除包: id={} uuid={}", target.getId(), target.getUUID());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 广播移除包失败: {}", t.toString());
        }
    }

    public static int sent() {
        return SENT.get();
    }
}
