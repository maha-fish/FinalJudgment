package com.maha_fish.finaljudgment.client;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端死亡状态清理（P0）。
 *
 * <p>服务端 {@code DeadState.guardTick} 已复位，但客户端本地玩家的 {@code deathTime}
 * 与服务端不同步时会持续播放死亡动画/白烟。这里每 tick 在客户端把它们清掉。</p>
 */
@Mod.EventBusSubscriber(modid = com.maha_fish.finaljudgment.common.Constants.MOD_ID, value = Dist.CLIENT)
public final class ClientDeathGuard {

    private ClientDeathGuard() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            ClientGhostGuard.cleanup();
            if (player.deathTime != 0) {
                player.deathTime = 0;
            }
            if (player.isDeadOrDying()) {
                float target = com.maha_fish.finaljudgment.core.ShadowHealth.get(
                        player.getUUID(), player.getMaxHealth());
                if (target < 1.0F) {
                    target = 1.0F;
                }
                player.setHealth(Math.min(player.getMaxHealth(), target));
                player.deathTime = 0;
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 客户端死亡状态清理异常: {}", t.toString());
        }
    }
}