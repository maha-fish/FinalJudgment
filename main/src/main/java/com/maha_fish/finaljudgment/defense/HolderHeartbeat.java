package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.attack.DeadState;
import com.maha_fish.finaljudgment.core.ShadowHealth;
import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 持有者心跳校验（P2，参考项目 {@code HeartbeatThread} 的做法）。
 *
 * <p>由 50ms 心跳线程驱动：在服务端线程上对每个持锚点玩家做死亡状态复位与血量兜底。
 * 与 {@code DefenseTicker}（20 次/秒）互补——心跳频率更高，能更快清掉"倒地"。</p>
 */
public final class HolderHeartbeat {

    private HolderHeartbeat() {
    }

    public static void beat() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        try {
            server.execute(() -> {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    try {
                        if (!T0Anchor.hasAnchorInInventory(player)) {
                            continue;
                        }
                        DeadState.guardTick(player);
                        float baseline = ShadowHealth.getBaseline(player.getUUID(), player.getMaxHealth());
                        if (baseline > 0.0F && player.getHealth() < baseline) {
                            player.setHealth(baseline);
                        }
                    } catch (Throwable ignored) {
                        // 单人失败不影响其他
                    }
                }
            });
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] HolderHeartbeat 异常: {}", t.toString());
        }
    }
}