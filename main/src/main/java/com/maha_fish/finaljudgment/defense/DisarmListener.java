package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 防缴械事件入口（7.12）。
 *
 * <p>识别"主动丢弃"（Q 键 / 界面拖出）→ 登记允许窗口（跨 tick），
 * 从而不误拦正常操作。玩家登出时清理追踪表（自检项）。</p>
 */
public final class DisarmListener {

    public DisarmListener() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onItemToss(ItemTossEvent event) {
        try {
            if (event.getPlayer() instanceof ServerPlayer player) {
                AntiDisarm.allowAnyChange(player);
                AntiDisarm.allowAnyChange(player.getInventory());
                FinalJudgment.LOGGER.debug("[FJ] 主动丢出物品，允许槽位变化: {}", player.getUUID());
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] ItemToss 处理异常: {}", t.toString());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (event.getEntity() != null) {
                AntiDisarm.forget(event.getEntity().getUUID());
                DefenseHandler.clearCounter(event.getEntity().getUUID());
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 登出清理异常: {}", t.toString());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 服务端停止：追踪表无需持久化（无跨存档状态）
        FinalJudgment.LOGGER.debug("[FJ] 服务端停止，防缴械追踪表随进程结束");
    }
}
