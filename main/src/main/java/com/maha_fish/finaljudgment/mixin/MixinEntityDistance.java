package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.defense.SelfContext;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让对方无法选中持有者（扩展 7-11）：距离判定对外部调用者放大。
 *
 * <p>对方的寻敌逻辑通常按 {@code distanceTo} 排序/阈值过滤，放大后自然排除持有者。</p>
 */
@Mixin(Entity.class)
public class MixinEntityDistance {

    @Inject(method = "distanceTo(Lnet/minecraft/world/entity/Entity;)F",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$distanceTo(Entity target, CallbackInfoReturnable<Float> cir) {
        try {
            if (SelfContext.shouldHide(target)) {
                cir.setReturnValue(1.0E6F);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Inject(method = "distanceToSqr(Lnet/minecraft/world/entity/Entity;)D",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$distanceToSqr(Entity target, CallbackInfoReturnable<Double> cir) {
        try {
            if (SelfContext.shouldHide(target)) {
                cir.setReturnValue(1.0E12D);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}