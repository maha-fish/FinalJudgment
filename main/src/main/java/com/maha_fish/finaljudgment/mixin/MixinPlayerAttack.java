package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.attack.AttackHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 攻击直通（参考项目 {@code MixinPlayer} 的功能对应物，Unit 11b）。
 *
 * <p>在 {@code Player.attack(Entity)} 入口把我们自己的击杀链接上：
 * 这样即便某个模组取消了 {@code AttackEntityEvent}，玩家用原版攻击依然会触发我们的链。</p>
 *
 * <p>只在服务端、且目标为 {@link LivingEntity} 时生效；不取消原版攻击。</p>
 */
@Mixin(Player.class)
@SuppressWarnings("mapping")
public class MixinPlayerAttack {

    @Inject(method = "attack", at = @At("HEAD"), require = 0)
    private void fj$onAttack(Entity target, CallbackInfo ci) {
        try {
            Player player = (Player) (Object) this;
            if (player.level().isClientSide || !(target instanceof LivingEntity living)) {
                return;
            }
            AttackHandler.onAttackEntity(player, living);
        } catch (Throwable ignored) {
            // 绝不影响原版攻击
        }
    }
}
