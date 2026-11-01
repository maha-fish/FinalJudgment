package com.maha_fish.finaljudgment.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LivingEntity.dead 访问器（Unit 11b）。 */
@Mixin(LivingEntity.class)
@SuppressWarnings("mapping")
public interface LivingEntityAccessor {

    @Accessor("dead")
    boolean fj$isDead();

    @Accessor("dead")
    void fj$setDead(boolean dead);
}
