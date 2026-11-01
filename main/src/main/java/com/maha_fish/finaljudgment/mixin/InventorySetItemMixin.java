package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.defense.AntiDisarm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 防缴械的 setItem 拦截（7.12，Unit 12）。
 *
 * <p>在槽位被写入前判断：原本是锚点武器、新值为空、且不在允许窗口内 → <b>取消写入</b>。
 * 这是"被动移除"最快的拦截点（早于 tick 快照还原，避免出现一帧的空手状态）。</p>
 *
 * <p>允许窗口按 <b>Inventory 实例</b>做键（每个玩家只有一个 Inventory，身份稳定），
 * 由 {@code ItemTossEvent} 登记，从而不误拦主动丢弃。</p>
 */
@Mixin(Inventory.class)
@SuppressWarnings("mapping")
public class InventorySetItemMixin {

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$guardSetItem(int slot, ItemStack incoming, CallbackInfo ci) {
        try {
            Inventory inventory = (Inventory) (Object) this;
            if (AntiDisarm.shouldBlockSetItem(inventory, slot, incoming)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 拦截失败时放行，交给 tick 快照还原兜底
        }
    }
}
