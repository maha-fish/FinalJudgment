package com.maha_fish.finaljudgment.core;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 登出监听（7c）：把"清理隔离状态"接到 Forge 事件总线。 */
public final class LogoutListener {

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        IsolationManager.onLogout(event);
    }
}
