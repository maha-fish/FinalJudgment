package com.maha_fish.finaljudgment.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * SynchedEntityData.DataItem.value 的 Accessor（Unit 11b）。
 * DataItem&lt;T&gt; 的 value 擦除后是 Object。
 */
@Mixin(SynchedEntityData.DataItem.class)
@SuppressWarnings("mapping")
public interface DataItemAccessor {

    @Accessor("value")
    Object fj$getValue();

    @Accessor("value")
    void fj$setValue(Object value);
}