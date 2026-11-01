package com.maha_fish.finaljudgment.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Entity Accessor/Invoker（Unit 11b）。
 *
 * <p>提供 7.8 描述符表里的两条：{@code removalReason} 字段、{@code setRemoved} 方法。
 * 走 Mixin 的 refmap，生产环境由 reobf 自动映射 SRG 名。</p>
 */
@Mixin(Entity.class)
@SuppressWarnings("mapping")
public interface EntityAccessor {

    @Accessor("removalReason")
    Entity.RemovalReason fj$getRemovalReason();

    @Accessor("removalReason")
    void fj$setRemovalReason(Entity.RemovalReason reason);

    @Invoker("setRemoved")
    void fj$invokeSetRemoved(Entity.RemovalReason reason);
}
