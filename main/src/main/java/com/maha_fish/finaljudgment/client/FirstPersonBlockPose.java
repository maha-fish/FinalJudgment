package com.maha_fish.finaljudgment.client;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 第一人称格挡姿态（自绘，接管渲染）。
 *
 * <p>为什么必须接管：原版 {@code ItemInHandRenderer} 的 {@code UseAnim.BLOCK} 分支
 * <b>只对盾牌</b>做格挡渲染，普通物品即使声明 BLOCK 也不会被画出来
 * ——这就是"第三人称能看到、第一人称看不见"的根因。</p>
 *
 * <p>做法：取消原版手部渲染，用原版手部基准位移 {@code (0.56, -0.52, -0.72)}
 * 加一组格挡旋转，直接调用 {@code ItemRenderer.renderStatic} 画出来。</p>
 */
@Mod.EventBusSubscriber(modid = com.maha_fish.finaljudgment.common.Constants.MOD_ID, value = Dist.CLIENT)
public final class FirstPersonBlockPose {

    private FirstPersonBlockPose() {
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            if (player == null || event.getHand() != InteractionHand.MAIN_HAND) {
                return;
            }
            ItemStack stack = event.getItemStack();
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (stack.getUseAnimation() != UseAnim.BLOCK) {
                return;
            }
            if (!player.isUsingItem() || player.getUsedItemHand() != InteractionHand.MAIN_HAND) {
                return;
            }
            if (!ItemStack.isSameItemSameTags(player.getUseItem(), stack)) {
                return;
            }

            event.setCanceled(true);

            PoseStack poseStack = event.getPoseStack();
            MultiBufferSource buffer = event.getMultiBufferSource();
            poseStack.pushPose();
            // 原版手部基准位移（右手）
            poseStack.translate(0.56D, -0.52D, -0.72D);
            // 格挡姿态：竖剑于身前、略向内倾
            poseStack.mulPose(Axis.YP.rotationDegrees(-30.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(12.0F));
            poseStack.translate(0.0D, 0.12D, 0.0D);

            minecraft.getItemRenderer().renderStatic(
                    stack,
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                    event.getPackedLight(),
                    OverlayTexture.NO_OVERLAY,
                    poseStack,
                    buffer,
                    player.level(),
                    player.getId());
            poseStack.popPose();
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 第一人称格挡渲染异常: {}", t.toString());
        }
    }
}