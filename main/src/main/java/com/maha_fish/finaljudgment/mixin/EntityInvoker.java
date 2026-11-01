package com.maha_fish.finaljudgment.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Entity 的 Invoker（Unit 11b / 12）。
 *
 * <p>提供击杀链"第 4 段：反射移除"与位置恢复所需的内部方法调用，
 * 走 refmap 映射，生产环境自动替换 SRG 名。</p>
 */
@Mixin(Entity.class)
@SuppressWarnings("mapping")
public interface EntityInvoker {

    @Invoker("remove")
    void fj$invokeRemove(Entity.RemovalReason reason);

    @Invoker("setPosRaw")
    void fj$invokeSetPosRaw(double x, double y, double z);
}
