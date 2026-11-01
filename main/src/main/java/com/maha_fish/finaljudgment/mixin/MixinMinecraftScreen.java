package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.KillFlagBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 屏幕层保护（补齐示例项目 {@code MixinMinecraftScreen}）。
 *
 * <p>持有锚点武器的玩家已被 {@code MixinPlayer} 拦下死亡流程；
 * 但如果别的 mod 强行打开 {@link DeathScreen}，这里再兜一次：直接取消打开。</p>
 */
@Mixin(Minecraft.class)
public class MixinMinecraftScreen {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$blockDeathScreen(Screen screen, CallbackInfo ci) {
        try {
            if (!(screen instanceof DeathScreen)) {
                return;
            }
            Object player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            if (KillFlagBridge.getProtectedPlayer() == player) {
                ci.cancel();
                FinalJudgment.LOGGER.debug("[FJ] 已拦截 DeathScreen 打开（持有者受保护）");
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}