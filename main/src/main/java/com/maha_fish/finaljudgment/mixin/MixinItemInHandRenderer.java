package com.maha_fish.finaljudgment.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称格挡姿态。
 *
 * <p>问题：{@code UseAnim.BLOCK} 会让第三人称摆出格挡，但第一人称原版只做普通的"使用中"位移，
 * 看不到举剑动作。因此这里在 {@code ItemInHandRenderer#renderArmWithItem} 头部把 PoseStack
 * 变换成"举剑横挡"的位置/角度，再由原版把物品画出来。</p>
 *
 * <p>触发条件严格限制：主手、正在使用、使用动画为 BLOCK、且使用中的正是当前渲染的物品。</p>
 */
@Mixin(ItemInHandRenderer.class)
public class MixinItemInHandRenderer {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), require = 0)
    private void fj$blockPoseFirstPerson(AbstractClientPlayer player, float partialTicks, float pitchAngle,
                                        InteractionHand hand, float swingProgress, ItemStack stack,
                                        float equipProgress, PoseStack poseStack,
                                        MultiBufferSource buffer, int combinedLight, CallbackInfo ci) {
        try {
            if (player == null || stack == null || stack.isEmpty()) {
                return;
            }
            if (hand != InteractionHand.MAIN_HAND) {
                return;
            }
            if (stack.getUseAnimation() != UseAnim.BLOCK) {
                return;
            }
            if (!player.isUsingItem() || player.getUsedItemHand() != hand) {
                return;
            }
            ItemStack using = player.getUseItem();
            if (!ItemStack.isSameItemSameTags(using, stack)) {
                return;
            }
            // 举剑横挡：略微上移、内收、向左旋转，形成"挡住视线"的姿态
            // 幅度收敛：只做轻微抬升与倾斜，保证剑始终在画面内（此前幅度过大导致剑被推出视野）
            poseStack.translate(0.02D, -0.04D, 0.02D);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-10.0F));
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-16.0F));
        } catch (Throwable ignored) {
            // 任何异常都不影响原版渲染
        }
    }
}