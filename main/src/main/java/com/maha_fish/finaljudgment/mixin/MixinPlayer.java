package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.KillFlagBridge;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家死亡保护（补齐示例项目 {@code MixinPlayer} 的对应能力）。
 *
 * <p>当该玩家是 {@code KillFlag.protectedPlayer}（持有锚点武器）时，取消
 * {@code Player#die}：不进入死亡流程 → 不掉落物品、不触发重生界面。
 * 这是防缴械/防秒杀在玩家侧的最后一层兜底（coremod 层已有字节码守卫，
 * 这里补 Mixin 层，双保险）。</p>
 */
@Mixin(Player.class)
public class MixinPlayer {

    @Inject(method = "die", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$protectPlayerDeath(DamageSource source, CallbackInfo ci) {
        try {
            Player self = (Player) (Object) this;
            Object protectedPlayer = KillFlagBridge.getProtectedPlayer();
            if (protectedPlayer == self) {
                ci.cancel();
                FinalJudgment.LOGGER.debug("[FJ] 已拦截持有者的死亡流程（Player#die）");
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] Player#die 保护异常: {}", t.toString());
        }
    }
}
