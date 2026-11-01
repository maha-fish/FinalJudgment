package com.maha_fish.finaljudgment.network;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C 物品栏同步（防缴械客户端兜底）：服务端写回槽位后让客户端立刻显示正确物品。 */
public final class S2CSyncInventory {

    private final int slot;
    private final ItemStack stack;

    public S2CSyncInventory(int slot, ItemStack stack) {
        this.slot = slot;
        this.stack = stack == null ? ItemStack.EMPTY : stack;
    }

    public static void encode(S2CSyncInventory packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.slot);
        buffer.writeItem(packet.stack);
    }

    public static S2CSyncInventory decode(FriendlyByteBuf buffer) {
        return new S2CSyncInventory(buffer.readVarInt(), buffer.readItem());
    }

    public static void handle(S2CSyncInventory packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist != Dist.CLIENT) {
                return;
            }
            try {
                var player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }
                int slot = packet.slot;
                if (slot >= 0 && slot < player.getInventory().items.size()) {
                    player.getInventory().items.set(slot, packet.stack);
                } else if (slot >= 100 && slot < 100 + player.getInventory().armor.size()) {
                    player.getInventory().armor.set(slot - 100, packet.stack);
                } else if (slot >= 200 && slot < 200 + player.getInventory().offhand.size()) {
                    player.getInventory().offhand.set(slot - 200, packet.stack);
                }
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] S2CSyncInventory 处理失败: {}", t.toString());
            }
        });
        context.setPacketHandled(true);
    }
}