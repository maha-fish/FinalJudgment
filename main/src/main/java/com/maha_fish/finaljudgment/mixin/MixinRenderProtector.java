package com.maha_fish.finaljudgment.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 渲染保护（补齐示例项目 {@code MixinRenderProtector}）。
 *
 * <p>目标：被我们"保护"的实体（服务端 {@code RenderProtector} 标记）不会因为视锥剔除
 * 而在客户端"消失"。实现方式：对未移除的存活实体跳过剔除判定。</p>
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinRenderProtector {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$keepVisible(Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
                               double camX, double camY, double camZ,
                               CallbackInfoReturnable<Boolean> cir) {
        try {
            if (entity == null || entity.isRemoved() || entity.getRemovalReason() != null) {
                return;
            }
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity)) {
                return;
            }
            // 存活实体：给一点余量的包围盒，避免贴边时被剔除
            AABB box = entity.getBoundingBox().inflate(4.0D);
            if (frustum.isVisible(box)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}