package com.maha_fish.finaljudgment.network;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C 位置同步（omnimobs S2CSyncSetPos 同款思路）：服务端挪走实体后让客户端跟着同步。 */
public final class S2CSyncPos {

    private final int entityId;
    private final double x;
    private final double y;
    private final double z;

    public S2CSyncPos(int entityId, double x, double y, double z) {
        this.entityId = entityId;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static void encode(S2CSyncPos packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
    }

    public static S2CSyncPos decode(FriendlyByteBuf buffer) {
        return new S2CSyncPos(buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public static void handle(S2CSyncPos packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist != Dist.CLIENT) {
                return;
            }
            try {
                var level = Minecraft.getInstance().level;
                if (level == null) {
                    return;
                }
                Entity entity = level.getEntity(packet.entityId);
                if (entity != null) {
                    entity.setPos(packet.x, packet.y, packet.z);
                }
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] S2CSyncPos 处理失败: {}", t.toString());
            }
        });
        context.setPacketHandled(true);
    }
}