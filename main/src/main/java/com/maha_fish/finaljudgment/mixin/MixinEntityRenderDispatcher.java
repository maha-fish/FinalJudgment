package com.maha_fish.finaljudgment.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 渲染黑名单（ArcaneVortex RenderBlacklistHelper 的精简版）：
 * 已经带 RemovalReason 的实体不再绘制，避免"服务端已删、客户端还在画"的幽灵。
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$skipRemoved(Entity entity, double x, double y, double z, float rotationYaw,
                                float partialTicks, com.mojang.blaze3d.vertex.PoseStack poseStack,
                                net.minecraft.client.renderer.MultiBufferSource buffer,
                                int packedLight, CallbackInfo ci) {
        try {
            if (entity == null) {
                return;
            }
            if (entity.isRemoved() || entity.getRemovalReason() != null
                    || com.maha_fish.finaljudgment.client.ClientGhostGuard.isRemoved(entity.getId())) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}