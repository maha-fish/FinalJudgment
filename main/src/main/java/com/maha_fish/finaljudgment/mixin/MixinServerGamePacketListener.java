package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.attack.AttackHandler;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端包层注入（示例项目 {@code MixinServerGamePacketListener} 的等价实现）。
 *
 * <p><b>只对"右键交互"生效</b>：{@code ServerboundInteractPacket} 同时承载
 * 左键攻击（ATTACK）与右键交互（INTERACT），必须区分——否则左键也会触发 10 格范围攻击，
 * 造成"左键选中多个目标"。</p>
 *
 * <p>判定用 {@code packet.dispatch(Handler)}：只有走 {@code onInteraction} 才处理，
 * 走 {@code onAttack} 直接忽略（左键交给单目标链路）。</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerGamePacketListener {

    @Inject(method = "handleInteract", at = @At("HEAD"), require = 0)
    private void fj$onInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        try {
            ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
            if (player == null) {
                return;
            }
            if (!player.getMainHandItem().is(com.maha_fish.finaljudgment.ModItems.FINAL_JUDGMENT_SWORD.get())) {
                return;
            }
            final boolean[] interaction = {false};
            final boolean[] attack = {false};
            packet.dispatch(new ServerboundInteractPacket.Handler() {
                @Override
                public void onInteraction(InteractionHand hand) {
                    interaction[0] = true;
                }

                @Override
                public void onInteraction(InteractionHand hand, Vec3 pos) {
                    interaction[0] = true;
                }

                @Override
                public void onAttack() {
                    attack[0] = true;
                }
            });
            if (attack[0] || !interaction[0]) {
                return;                     // 左键攻击：不在这里处理，保证单目标
            }
            AttackHandler.onRangeAttack(player);   // 右键交互 = 10 格范围攻击
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] handleInteract 注入异常: {}", t.toString());
        }
    }
}
