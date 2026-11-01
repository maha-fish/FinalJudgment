package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.defense.SelfContext;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让对方无法选中持有者（扩展 7-11）：{@code isPickable} 对外部调用者返回 false。
 *
 * <p>只影响射线/拾取判定，不影响渲染；我方查询经 {@link SelfContext} 白名单放行。</p>
 */
@Mixin(Entity.class)
public class MixinEntityPickable {

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$hideFromPick(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (SelfContext.shouldHide((Entity) (Object) this)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}