package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 攻击心跳包（9c，包 ID 1）。客户端每 2 tick 发一次，服务端据此刷新心跳；
 * 心跳超时（10 tick）后 {@link ServerAutoAttack} 停止自动攻击。
 */
public final class AttackRequestPacket {

    public AttackRequestPacket() {
    }

    public static void encode(AttackRequestPacket packet, FriendlyByteBuf buffer) {
        // 无负载
    }

    public static AttackRequestPacket decode(FriendlyByteBuf buffer) {
        return new AttackRequestPacket();
    }

    public static void handle(AttackRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            ServerAutoAttack.refreshHeartbeat(player);
        }
        context.setPacketHandled(true);
    }

    public static void send() {
        try {
            if (NetworkHandler.channel() != null) {
                NetworkHandler.channel().sendToServer(new AttackRequestPacket());
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 心跳包发送失败: {}", t.toString());
        }
    }
}
