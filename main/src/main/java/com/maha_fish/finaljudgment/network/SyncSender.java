package com.maha_fish.finaljudgment.network;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.NetworkHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

/** 双端同步的发送入口（服务端调用）。 */
public final class SyncSender {

    private SyncSender() {
    }

    /** 把实体位置推给该维度所有玩家（含自己）。 */
    public static void syncPos(ServerLevel level, Entity entity, double x, double y, double z) {
        if (level == null || entity == null || NetworkHandler.channel() == null) {
            return;
        }
        try {
            NetworkHandler.channel().send(PacketDistributor.DIMENSION.with(level::dimension),
                    new S2CSyncPos(entity.getId(), x, y, z));
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] syncPos 发送失败: {}", t.toString());
        }
    }

    /** 把指定槽位的真实物品推给单个玩家。slot：0..35 主背包，100..103 盔甲，200 副手。 */
    public static void syncInventory(ServerPlayer player, int slot, ItemStack stack) {
        if (player == null || NetworkHandler.channel() == null) {
            return;
        }
        try {
            NetworkHandler.channel().send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CSyncInventory(slot, stack));
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] syncInventory 发送失败: {}", t.toString());
        }
    }

    /** 同步玩家整条主背包（触发防缴械还原后调用）。 */
    public static void syncMainInventory(ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            syncInventory(player, slot, player.getInventory().items.get(slot));
        }
    }
}