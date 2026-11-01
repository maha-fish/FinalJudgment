package com.maha_fish.finaljudgment;

import com.maha_fish.finaljudgment.common.Constants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络层：通道 {@code finaljudgment:main}、协议版本 "1"。
 *
 * <p>包 ID 分配：</p>
 * <ul>
 *   <li>0 = {@code AttackPickPacket}（C2S 攻击选取请求）</li>
 *   <li>1 = {@code AttackRequestPacket}（C2S 攻击心跳）</li>
 *   <li>2 = {@code S2CSyncPos}（S2C 实体位置同步）</li>
 *   <li>3 = {@code S2CSyncInventory}（S2C 物品栏同步）</li>
 * </ul>
 */
public final class NetworkHandler {

    public static final String PROTOCOL_VERSION = "1";
    public static final ResourceLocation CHANNEL_NAME =
            new ResourceLocation(Constants.MOD_ID, "main");

    private static SimpleChannel channel;

    private NetworkHandler() {
    }

    public static void init() {
        channel = NetworkRegistry.newSimpleChannel(CHANNEL_NAME,
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals);
        FinalJudgment.LOGGER.info("[FJ] 网络通道已注册: {} (协议 {})", CHANNEL_NAME, PROTOCOL_VERSION);

        // 包 ID 0：攻击选取请求（9a）
        channel.messageBuilder(com.maha_fish.finaljudgment.attack.AttackPickPacket.class, 0)
                .encoder(com.maha_fish.finaljudgment.attack.AttackPickPacket::encode)
                .decoder(com.maha_fish.finaljudgment.attack.AttackPickPacket::decode)
                .consumerMainThread(com.maha_fish.finaljudgment.attack.AttackPickPacket::handle)
                .add();
        FinalJudgment.LOGGER.info("[FJ] 网络包已注册: ID 0 = AttackPickPacket");

        // 包 ID 1：攻击心跳（9c）
        channel.messageBuilder(com.maha_fish.finaljudgment.attack.AttackRequestPacket.class, 1)
                .encoder(com.maha_fish.finaljudgment.attack.AttackRequestPacket::encode)
                .decoder(com.maha_fish.finaljudgment.attack.AttackRequestPacket::decode)
                .consumerMainThread(com.maha_fish.finaljudgment.attack.AttackRequestPacket::handle)
                .add();
        FinalJudgment.LOGGER.info("[FJ] 网络包已注册: ID 1 = AttackRequestPacket");

        // 包 ID 2：实体位置同步（双端同步）
        channel.messageBuilder(com.maha_fish.finaljudgment.network.S2CSyncPos.class, 2)
                .encoder(com.maha_fish.finaljudgment.network.S2CSyncPos::encode)
                .decoder(com.maha_fish.finaljudgment.network.S2CSyncPos::decode)
                .consumerMainThread(com.maha_fish.finaljudgment.network.S2CSyncPos::handle)
                .add();
        FinalJudgment.LOGGER.info("[FJ] 网络包已注册: ID 2 = S2CSyncPos（位置同步）");

        // 包 ID 3：物品栏同步（防缴械兜底）
        channel.messageBuilder(com.maha_fish.finaljudgment.network.S2CSyncInventory.class, 3)
                .encoder(com.maha_fish.finaljudgment.network.S2CSyncInventory::encode)
                .decoder(com.maha_fish.finaljudgment.network.S2CSyncInventory::decode)
                .consumerMainThread(com.maha_fish.finaljudgment.network.S2CSyncInventory::handle)
                .add();
        FinalJudgment.LOGGER.info("[FJ] 网络包已注册: ID 3 = S2CSyncInventory（物品栏同步）");
    }

    public static SimpleChannel channel() {
        return channel;
    }
}
