package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.attack.SpawnVeto;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 生成拦截的最底层（对应 ArcaneVortex Agent/EntitySpawn/EntitySpawnInterceptor）。
 *
 * <p>1.20.1 的所有"加入世界"路径最终都汇流到 {@code ServerLevel#addEntity(Entity)}：</p>
 * <pre>
 *   addFreshEntity(Entity)                → addEntity(...)
 *   addWithUUID(Entity)                   → addEntity(...)
 *   tryAddFreshEntityWithPassengers(Entity)→ addFreshEntity(...) → addEntity(...)
 * </pre>
 *
 * <p>因此只挂 {@code addEntity} 即可覆盖全部入口，且它比 Forge 的
 * {@code EntityJoinLevelEvent} 更靠底层（事件可被其他 mod 取消/跳过，这里不会）。</p>
 *
 * <p>被击杀过的实体 UUID 在 {@link SpawnVeto} 黑名单里，直接返回 false，实体进不了世界。</p>
 */
@Mixin(ServerLevel.class)
public class MixinServerLevelSpawn {

    @Inject(method = "addEntity(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$vetoAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (SpawnVeto.shouldVeto(entity)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] addEntity 拦截异常: {}", t.toString());
        }
    }

    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$vetoAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (SpawnVeto.shouldVeto(entity)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] addFreshEntity 拦截异常: {}", t.toString());
        }
    }

    @Inject(method = "addWithUUID(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void fj$vetoAddWithUUID(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (SpawnVeto.shouldVeto(entity)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] addWithUUID 拦截异常: {}", t.toString());
        }
    }
}