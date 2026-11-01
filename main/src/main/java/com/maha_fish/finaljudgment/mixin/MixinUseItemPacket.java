package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.attack.AttackHandler;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 右键最底层兜底：直接挂在服务端"使用物品"网络包处理入口上。
 *
 * <p>层级关系（越往下越难被拦截）：</p>
 * <ol>
 *   <li>Forge 事件（RightClickItem / RightClickBlock）—— 任何 mod 都能取消</li>
 *   <li>Item.use() —— 可被别的 mod 的 use 逻辑抢先或取消</li>
 *   <li><b>ServerboundUseItemPacket 处理（本类）</b> —— 客户端按下右键后服务端必经之路，
 *       与物品实现无关，别的 mod 即使取消了 use() 的结果也拦不住这里</li>
 * </ol>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinUseItemPacket {

    @Inject(method = "handleUseItem", at = @At("HEAD"), require = 0)
    private void fj$onUseItemPacket(ServerboundUseItemPacket packet, CallbackInfo ci) {
        try {
            ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
            if (player == null) {
                return;
            }
            if (!player.getMainHandItem().is(com.maha_fish.finaljudgment.ModItems.FINAL_JUDGMENT_SWORD.get())) {
                return;
            }
            if (PACKET_LOG.compareAndSet(false, true)) {
                FinalJudgment.LOGGER.info("[FJ] 右键包已到达服务端（handleUseItem）");
            }
            AttackHandler.onRangeAttack(player);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] handleUseItem 兜底异常: {}", t.toString());
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean PACKET_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);
}