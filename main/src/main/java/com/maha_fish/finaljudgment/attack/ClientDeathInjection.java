package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端死亡注入（7.5 功能 9）。
 *
 * <p>服务端侧：下发 {@link ClientboundPlayerCombatKillPacket} 让客户端进入死亡界面。
 * 客户端侧"拦 PERFORM_RESPAWN"需要 Mixin（Unit 11b），这里先提供能力与状态位。</p>
 */
public final class ClientDeathInjection {

    private static volatile boolean intercepted;

    private ClientDeathInjection() {
    }

    /** 给玩家下发死亡界面。 */
    public static void sendDeathScreen(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            player.connection.send(new ClientboundPlayerCombatKillPacket(
                    player.getId(), Component.translatable("death.attack.generic", player.getDisplayName())));
            FinalJudgment.LOGGER.debug("[FJ] 已下发死亡界面: {}", player.getUUID());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 下发死亡界面失败: {}", t.toString());
        }
    }

    /** 客户端拦截复活请求（11b 的 Mixin 调用此方法记录状态）。 */
    public static void markIntercepted() {
        intercepted = true;
    }

    public static boolean intercepted() {
        return intercepted;
    }
}
