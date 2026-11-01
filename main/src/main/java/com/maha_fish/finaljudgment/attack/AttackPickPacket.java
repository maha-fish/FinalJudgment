package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.NetworkHandler;
import com.maha_fish.finaljudgment.attack.RayTraceUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 选取请求包（Unit 9a）：客户端"我要选目标" → 服务端做真正的距离/夹角判定。
 *
 * <p>为什么必须有这条包：{@code LeftClickEmpty} 是**纯客户端**事件，
 * 客户端 {@code ClientLevel} 拿不到可靠的服务端实体表；而原版
 * {@code ServerboundInteractPacket} 在服务端有 8 格距离校验，远距离会被直接丢弃。
 * 所以远距离选目标只能走自己的包。</p>
 */
public final class AttackPickPacket {

    /** 0=普通近战(6格) 1=Shift+左键(无限距离) 2=右键远程(64格) */
    private final byte mode;

    public AttackPickPacket(byte mode) {
        this.mode = mode;
    }

    public static void encode(AttackPickPacket packet, FriendlyByteBuf buffer) {
        buffer.writeByte(packet.mode);
    }

    public static AttackPickPacket decode(FriendlyByteBuf buffer) {
        return new AttackPickPacket(buffer.readByte());
    }

    private static final java.util.concurrent.atomic.AtomicBoolean[] LOGGED = {
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicBoolean(false)};

    public static void handle(AttackPickPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null && packet.mode >= 0 && packet.mode < LOGGED.length
                && LOGGED[packet.mode].compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 服务端收到选取包 mode={}（首次）", packet.mode);
        }
        if (player != null) {
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            if (level != null) {
                // 右键（mode 2）= 10 格球形范围攻击：走 onRangeAttack，不做圆锥/单体筛选
                // （此前误用 pickRemote → cos>0.80 即 ≈36.9° 圆锥且只打一个，正是"只能扫到 30 度角"的原因）
                if (packet.mode == 2) {
                    AttackHandler.onRangeAttack(player);
                    context.setPacketHandled(true);
                    return;
                }
                LivingEntity target = switch (packet.mode) {
                    case 1 -> RayTraceUtil.pickUnlimited(level, player);
                    default -> RayTraceUtil.pickMelee(level, player);
                };
                AttackHandler.onPickResult(player, target, packet.mode);
            }
        }
        context.setPacketHandled(true);
    }

    public static void send(byte mode) {
        try {
            if (NetworkHandler.channel() != null) {
                NetworkHandler.channel().sendToServer(new AttackPickPacket(mode));
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 选取请求发送失败: {}", t.toString());
        }
    }
}
