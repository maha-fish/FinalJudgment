package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.KillFlagBridge;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 无敌绕过（Unit 11b）：被击杀链锁定的目标视为"可受伤"。
 *
 * <p>很多模组实体把自己标成 invulnerable 来免疫一切；这里只对
 * {@code KillFlag.targetEntity} 生效，不影响其他实体。</p>
 */
@Mixin(Entity.class)
@SuppressWarnings("mapping")
public class MixinEntityInvulnerable {

    @Inject(method = "isInvulnerable", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$bypassInvulnerable(CallbackInfoReturnable<Boolean> cir) {
        try {
            Object target = KillFlagBridge.getTargetEntity();
            if (target == (Object) this) {
                cir.setReturnValue(Boolean.FALSE);
            }
        } catch (Throwable ignored) {
            // 保持原行为
        }
    }
}
