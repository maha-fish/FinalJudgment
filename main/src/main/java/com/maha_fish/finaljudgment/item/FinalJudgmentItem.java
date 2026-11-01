package com.maha_fish.finaljudgment.item;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.LayerStatus;
import com.maha_fish.finaljudgment.attack.KillChain;
import com.maha_fish.finaljudgment.attack.ServerAutoAttack;
import com.maha_fish.finaljudgment.attack.TrackingAttack;
import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 终焉裁定（7.10）。
 *
 * <p>硬约束（规范 4/32）：`stacksTo(1)` + `fireResistant` + 攻击 +3 / 速度 -2.4 +
 * `hurtEnemy` 只处理 ServerPlayer + `use()` 返回 `pass()` + `inventoryTick` **只在服务端**重写锚点 +
 * `appendHoverText` 显示四层状态 + `isFoil` 恒真。</p>
 */
public final class FinalJudgmentItem extends SwordItem {


    /** 自定义 Tier：不可损坏（配合 stacksTo(1)），高附魔值。 */
    private static final Tier TIER = new Tier() {
        @Override
        public int getUses() {
            return 0;
        }

        @Override
        public float getSpeed() {
            return 12.0F;
        }

        @Override
        public float getAttackDamageBonus() {
            return 3.0F;
        }

        @Override
        public int getLevel() {
            return 4;
        }

        @Override
        public int getEnchantmentValue() {
            return 22;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.EMPTY;
        }
    };

    public FinalJudgmentItem() {
        super(TIER, 3, -2.4F, new Item.Properties().stacksTo(1).fireResistant());
    }

    /** 附魔光效恒显（辨识度）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /**
     * 右键格挡的<b>视觉</b>（1.9 之前的举剑姿态，仅视觉、不减伤）。
     *
     * <p>做法：{@code getUseAnimation = BLOCK} + 长 use 时长 + {@code startUsingItem}。
     * 与规范"use() 必须返回 pass()"冲突——要出姿态就必须进入 using 状态；
     * 用 {@code -Dfj.sword.blockPose=false} 可回到 pass() 行为。</p>
     */
    /** 右键行为：与规范一致，返回 pass()（不进入 using，不做格挡视觉）。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 右键：①进入 BLOCK 使用状态（格挡姿态，第一/第三人称可见） ②服务端执行范围攻击
        if (hand == InteractionHand.MAIN_HAND) {
            player.startUsingItem(hand);
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                try {
                    com.maha_fish.finaljudgment.attack.AttackHandler.onRangeAttack(serverPlayer);
                } catch (Throwable t) {
                    FinalJudgment.LOGGER.debug("[FJ] use() 范围攻击异常: {}", t.toString());
                }
            }
            return InteractionResultHolder.consume(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    /** 格挡姿态：使用动画为 BLOCK（第一人称与第三人称都会摆出举剑格挡）。 */
    @Override
    public net.minecraft.world.item.UseAnim getUseAnimation(ItemStack stack) {
        return net.minecraft.world.item.UseAnim.BLOCK;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    /** 第三人称手臂姿态（Forge 客户端钩子，供 HumanoidModel 使用）。 */
    @Override
    public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new net.minecraftforge.client.extensions.common.IClientItemExtensions() {
            @Override
            public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(net.minecraft.world.entity.LivingEntity entity,
                                                                                 InteractionHand hand, ItemStack stack) {
                if (entity.isUsingItem() && entity.getUseItem().equals(stack)
                        && stack.getUseAnimation() == net.minecraft.world.item.UseAnim.BLOCK) {
                    return net.minecraft.client.model.HumanoidModel.ArmPose.BLOCK;
                }
                return net.minecraft.client.model.HumanoidModel.ArmPose.ITEM;
            }
        });
    }

    private static final java.util.concurrent.atomic.AtomicBoolean USE_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean CLICK_LOG =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 左键命中实体：参照 ArcaneVortex 的 onLeftClickEntity；Shift 时改为远程。 */
    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, net.minecraft.world.entity.Entity entity) {
        try {
            if (!(entity instanceof LivingEntity target) || !(player instanceof ServerPlayer serverPlayer)) {
                return false;
            }
            if (CLICK_LOG.compareAndSet(false, true)) {
                FinalJudgment.LOGGER.info("[FJ] onLeftClickEntity 已触发: shift={}", player.isShiftKeyDown());
            }
            if (player.isShiftKeyDown()) {
                com.maha_fish.finaljudgment.attack.AttackHandler.onRemote(serverPlayer);   // Shift+左键 = 远程
            } else {
                com.maha_fish.finaljudgment.attack.AttackHandler.onAttackEntity(serverPlayer, target);  // 左键 = 近战
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] onLeftClickEntity 异常: {}", t.toString());
        }
        return false;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        try {
            if (attacker instanceof ServerPlayer player && target.level() instanceof ServerLevel level) {
                T0Anchor.mark(stack);
                ServerAutoAttack.commitTarget(player, target);
                TrackingAttack.track(level, target);
                KillChain.execute(level, target);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] hurtEnemy 处理异常: {}", t.toString());
        }
        return true;
    }

    /** 服务端每次 tick 重写锚点（抗 NBT 清除）。 */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide && entity instanceof Player) {
            T0Anchor.mark(stack);
        }
    }

    /** 四层状态单行显示（方案 B）。 */
    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(LayerStatus.tooltipLine()));
        tooltip.add(Component.translatable("item.finaljudgment.final_judgment_sword.desc")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
