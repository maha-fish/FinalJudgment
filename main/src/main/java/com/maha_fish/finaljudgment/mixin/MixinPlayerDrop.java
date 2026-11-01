package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.KillFlagBridge;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 防缴械（Unit 10 / 11b）：持有者不会执行 dropEquipment()。 */
@Mixin(Player.class)
@SuppressWarnings("mapping")
public class MixinPlayerDrop {

    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$guardDropEquipment(CallbackInfo ci) {
        try {
            if (KillFlagBridge.getProtectedPlayer() == (Object) this) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 放行
        }
    }
}
