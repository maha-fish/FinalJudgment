package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.attack.EntityRegistry;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体观察补漏：只要实体还在 tick，就把它登记进我们的弱引用注册表。
 *
 * <p>对付"主动隐藏自己"的实体：它们常见做法是把自身从 EntityLookup / EntitySection 里摘掉
 * （于是 {@code level.getAllEntities()} 看不到），但仍留在 EntityTickList 里继续 tick。
 * 我们在 {@code Entity#tick} 头部登记，等于"凡是活着的、会动的，都逃不过记录"。</p>
 */
@Mixin(Entity.class)
public class MixinEntityTickCapture {

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void fj$captureTickingEntity(CallbackInfo ci) {
        try {
            EntityRegistry.remember((Entity) (Object) this);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}