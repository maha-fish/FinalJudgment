package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;

/**
 * 带锚点的武器（测试用，Unit 11a 会做正式版）。
 *
 * <p>锚点写入有两个时机：① 物品创建时（{@link #getDefaultInstance()} 拿不到 stack，
 * 所以改为拿到 stack 后自愈）② 每次被使用时（{@link #inventoryTick}）。
 * 这样即使玩家是从创造模式物品栏直接拖出来的，第一次拿在手上就会有锚点。</p>
 */
public class AnchorWeaponItem extends SwordItem {

    public AnchorWeaponItem(Tier tier, int damage, float speed, Properties properties) {
        super(tier, damage, speed, properties);
        // 构造时 tier 可能是 null（注册期惰性求值），Tiers 作为兜底
    }

    public AnchorWeaponItem(int damage, float speed, Properties properties) {
        this(Tiers.IRON, damage, speed, properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, net.minecraft.world.level.Level level,
                              net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide && entity instanceof Player) {
            // 自愈：把锚点补上（只对这一种武器生效，不会污染其他物品）
            T0Anchor.mark(stack);
        }
    }
}
