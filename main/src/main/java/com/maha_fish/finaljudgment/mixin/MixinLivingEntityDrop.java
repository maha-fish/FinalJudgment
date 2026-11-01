package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.attack.SilenceKillUtil;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 静默击杀（Unit 11b / 7.5 功能 7）：静默目标不产生死亡掉落。 */
@Mixin(LivingEntity.class)
@SuppressWarnings("mapping")
public class MixinLivingEntityDrop {

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$suppressLoot(DamageSource source, CallbackInfo ci) {
        try {
            if (SilenceKillUtil.suppressLoot((LivingEntity) (Object) this)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 放行
        }
    }
}
