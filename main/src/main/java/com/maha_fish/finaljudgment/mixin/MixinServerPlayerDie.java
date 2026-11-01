package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.KillFlagBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家死亡守卫（Unit 11b）：持有者的 ServerPlayer.die() 被取消。 */
@Mixin(ServerPlayer.class)
@SuppressWarnings("mapping")
public class MixinServerPlayerDie {

    @Inject(method = "die", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$guardDie(DamageSource source, CallbackInfo ci) {
        try {
            if (KillFlagBridge.getProtectedPlayer() == (Object) this) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 放行
        }
    }
}
