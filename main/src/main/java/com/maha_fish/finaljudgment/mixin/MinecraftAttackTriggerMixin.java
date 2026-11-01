package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.attack.AttackPickPacket;
import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 左键检测（7.7，Unit 11b）。
 *
 * <p>状态机：按下 → 立即发一次选取请求；持续按住 → 每 {@link #FJ_REPEAT_INTERVAL} tick 补发；
 * 超过 {@link #FJ_REPEAT_TIMEOUT} tick 未再按下则视为释放（避免"一直按住"变成连发）。</p>
 *
 * <p><b>客户端类，只能在客户端加载</b>（规范 25）：本 Mixin 只在 mixins.json 的 client 段出现。</p>
 */
@Mixin(Minecraft.class)
@SuppressWarnings("mapping")
public class MinecraftAttackTriggerMixin {

    @Unique
    private static final int FJ_REPEAT_INTERVAL = 2;
    @Unique
    private static final int FJ_REPEAT_TIMEOUT = 40;

    @Unique
    private long fj$lastTriggerTick = -1000L;
    @Unique
    private long fj$lastFireTick = -1000L;
    @Unique
    private boolean fj$firing;

    /** 在 runTick 开头采样左键状态（每 tick 一次）。 */
    @Inject(method = "runTick", at = @At("HEAD"), require = 1)
    private void fj$sampleAttack(boolean renderLevel, CallbackInfo ci) {
        try {
            Minecraft minecraft = (Minecraft) (Object) this;
            Player player = minecraft.player;
            if (player == null || minecraft.screen != null) {
                fj$firing = false;
                return;
            }
            if (!T0Anchor.hasAnchorInMainHand(player)) {
                fj$firing = false;
                return;
            }
            boolean pressed = minecraft.options.keyAttack.isDown();
            long now = player.level().getGameTime();
            if (pressed) {
                if (!fj$firing || now - fj$lastFireTick >= FJ_REPEAT_INTERVAL) {
                    AttackPickPacket.send((byte) (player.isShiftKeyDown() ? 1 : 0));
                    fj$lastFireTick = now;
                    fj$lastTriggerTick = now;
                }
                fj$firing = true;
            } else {
                if (fj$firing && now - fj$lastTriggerTick > FJ_REPEAT_TIMEOUT) {
                    fj$firing = false;      // 超时视为释放
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 左键检测异常: {}", t.toString());
        }
    }
}
