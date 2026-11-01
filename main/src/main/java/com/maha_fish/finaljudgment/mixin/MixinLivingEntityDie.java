package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.KillFlagBridge;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 死亡守卫（Unit 11b）：持有者不会进入 die()。 */
@Mixin(LivingEntity.class)
@SuppressWarnings("mapping")
public class MixinLivingEntityDie {

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
