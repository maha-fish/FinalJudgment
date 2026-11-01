package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.KillFlagBridge;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 血量守卫（Unit 11b）：持有者（protectedPlayer）的回血/扣血一律忽略。 */
@Mixin(LivingEntity.class)
@SuppressWarnings("mapping")
public class MixinLivingEntityHealth {

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$guardSetHealth(float health, CallbackInfo ci) {
        try {
            if (KillFlagBridge.getProtectedPlayer() == (Object) this) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 放行
        }
    }
    @Inject(method = "getHealth", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$decoyHealth(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        try {
            LivingEntity self = (LivingEntity) (Object) this;
            // 欺骗进攻优先：伪造血量
            Float bait = com.maha_fish.finaljudgment.defense.GetterProbe.getFloat(self, "health");
            if (bait != null) {
                cir.setReturnValue(bait);
                return;
            }
            if (!com.maha_fish.finaljudgment.defense.DeceptionStore.isActive(self)) {
                return;
            }
            if (com.maha_fish.finaljudgment.defense.DeceptionContext.isSelf()) {
                return;                       // 我方读真值
            }
            cir.setReturnValue(com.maha_fish.finaljudgment.defense.DeceptionStore.decoyOf(self));
        } catch (Throwable ignored) {
            // 忽略
        }
    }}
