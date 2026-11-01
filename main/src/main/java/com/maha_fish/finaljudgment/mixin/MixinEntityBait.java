package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.defense.GetterProbe;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Getter 插桩（扩展 7-10）：对外部调用者返回"假死"状态。
 *
 * <p>覆盖 {@code isRemoved()} 与 {@code getRemovalReason()}；值来自
 * {@link GetterProbe} 的覆盖注册表，我方调用经 {@code DeceptionContext} 放行读真值。</p>
 */
@Mixin(Entity.class)
public class MixinEntityBait {

    @Inject(method = "isRemoved", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$baitIsRemoved(CallbackInfoReturnable<Boolean> cir) {
        try {
            Boolean override = GetterProbe.getBoolean((Entity) (Object) this, "isRemoved");
            if (override != null) {
                cir.setReturnValue(override);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Inject(method = "getRemovalReason", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$baitRemovalReason(CallbackInfoReturnable<Entity.RemovalReason> cir) {
        try {
            Object override = GetterProbe.getObject((Entity) (Object) this, "removalReason");
            if (override instanceof Entity.RemovalReason reason) {
                cir.setReturnValue(reason);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}