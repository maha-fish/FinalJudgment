package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 武器锚点（7.4.2 功能 2）。
 *
 * <p>锚点 = 物品 NBT 上的 {@code final_judgment_core = "v1"}。
 * <b>只重写已有锚点</b>：{@link #forceRestore(ItemStack)} 先判 {@code isAnchor}，
 * 绝不给普通物品加标记（否则整个背包都会被"变成"武器）。</p>
 */
public final class T0Anchor {

    private T0Anchor() {
    }

    /** 仅在创建武器时调用一次。 */
    public static void mark(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.getOrCreateTag().putString(Constants.ANCHOR_NBT_KEY, Constants.ANCHOR_NBT_VALUE);
    }

    public static boolean isAnchor(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return false;
        }
        return Constants.ANCHOR_NBT_VALUE.equals(
                stack.getTag().getString(Constants.ANCHOR_NBT_KEY));
    }

    /** 只重写已存在的锚点（NBT 被清/被改时恢复）。 */
    public static boolean forceRestore(ItemStack stack) {
        if (!isAnchor(stack)) {
            return false;
        }
        stack.getOrCreateTag().putString(Constants.ANCHOR_NBT_KEY, Constants.ANCHOR_NBT_VALUE);
        return true;
    }

    /** 按签名重建一把带锚点的武器（防缴械还原用；签名格式 {@code ANCHOR:<item>:<count>}）。 */
    public static ItemStack rebuildFromSignature(String signature) {
        if (signature == null || !signature.startsWith("ANCHOR:")) {
            return null;
        }
        try {
            ItemStack stack = new ItemStack(
                    com.maha_fish.finaljudgment.ModItems.TEST_WEAPON.get());
            mark(stack);
            return stack;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 锚点重建失败: {}", t.toString());
            return null;
        }
    }

    /** 只认主手：空手（或主手不是锚点武器）时必须完全不生效。 */
    public static boolean hasAnchorInMainHand(Player player) {
        if (player == null) {
            return false;
        }
        try {
            net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
            // 物品类型优先：避免"刚 /give 出来还没打上 NBT"时判定失败
            if (stack.is(com.maha_fish.finaljudgment.ModItems.FINAL_JUDGMENT_SWORD.get())
                    || stack.is(com.maha_fish.finaljudgment.ModItems.TEST_WEAPON.get())) {
                return true;
            }
            return isAnchor(stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 扫主背包 + 副手（盔甲槽/槽位追踪表在 Unit 10 的防缴械里扩展）。 */
    public static boolean hasAnchorInInventory(Player player) {
        if (player == null) {
            return false;
        }
        try {
            for (ItemStack stack : player.getInventory().items) {
                if (isAnchor(stack)
                        || stack.is(com.maha_fish.finaljudgment.ModItems.FINAL_JUDGMENT_SWORD.get())
                        || stack.is(com.maha_fish.finaljudgment.ModItems.TEST_WEAPON.get())) {
                    return true;
                }
            }
            if (isAnchor(player.getInventory().offhand.get(0))) {
                return true;
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 锚点扫描失败: {}", t.toString());
        }
        return false;
    }

    /** 主 mod 侧的自愈路径（武器 inventoryTick / DefenseHandler 每 tick 都会调）。 */
    public static void restoreInventory(Player player) {
        if (player == null) {
            return;
        }
        try {
            for (ItemStack stack : player.getInventory().items) {
                forceRestore(stack);
            }
            forceRestore(player.getInventory().offhand.get(0));
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 锚点重写失败: {}", t.toString());
        }
    }
}
