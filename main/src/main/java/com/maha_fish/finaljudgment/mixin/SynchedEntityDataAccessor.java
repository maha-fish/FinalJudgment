package com.maha_fish.finaljudgment.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * SynchedEntityData.itemsById 的 Accessor（Unit 11b）。
 *
 * 注意：返回类型必须是 Int2ObjectMap，不能用 Map&lt;Integer, ?&gt;：
 * 泛型不协变，Mixin 类型校验会拒绝，表现为 Could not locate @Accessor target itemsById。
 */
@Mixin(SynchedEntityData.class)
@SuppressWarnings("mapping")
public interface SynchedEntityDataAccessor {

    @Accessor("itemsById")
    Int2ObjectMap<SynchedEntityData.DataItem<?>> fj$getItemsById();
}