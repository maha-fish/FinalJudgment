package com.maha_fish.finaljudgment.core;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.mixin.DataItemAccessor;
import com.maha_fish.finaljudgment.mixin.SynchedEntityDataAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 写同步血量（7.4.1 功能 4，规范 12）。
 *
 * <p><b>首选 Mixin accessor 直写</b>（Unit 11b 已就位）：
 * {@code itemsById → DataItem.value}，绕过 {@code SynchedEntityData.set()} 的脏标记/回调。
 * accessor 不可用（Mixin 未 apply）时回退到公开 {@code set()}。</p>
 */
public final class SynchedEntityDataWriter {

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private SynchedEntityDataWriter() {
    }

    public static boolean writeHealth(LivingEntity entity, float health) {
        if (entity == null) {
            return false;
        }
        Object accessor = HealthField.accessor();
        if (accessor == null) {
            return false;
        }
        if (writeViaAccessor(entity, accessor, health)) {
            return true;
        }
        return writeViaSet(entity, accessor, health);
    }

    /** 路径 1：Mixin accessor 直写（最快、无回调）。 */
    private static boolean writeViaAccessor(LivingEntity entity, Object accessor, float health) {
        try {
            SynchedEntityData data = entity.getEntityData();
            if (!(data instanceof SynchedEntityDataAccessor accessorHolder)) {
                return false;
            }
            Int2ObjectMap<SynchedEntityData.DataItem<?>> items = accessorHolder.fj$getItemsById();
            if (items == null) {
                return false;
            }
            int id = HealthField.accessorId();
            SynchedEntityData.DataItem<?> item = items.get(id);
            if (item == null) {
                return false;
            }
            if (item instanceof DataItemAccessor valueHolder) {
                valueHolder.fj$setValue(health);
                if (LOGGED.compareAndSet(false, true)) {
                    FinalJudgment.LOGGER.info("[FJ] SynchedEntityDataWriter: 走 Mixin accessor 直写（accessor id={}）", id);
                }
                return true;
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] accessor 直写失败，回退 set(): {}", t.toString());
        }
        return false;
    }

    /** 路径 2：公开 set()（兜底）。 */
    private static boolean writeViaSet(LivingEntity entity, Object accessor, float health) {
        try {
            Object data = entity.getEntityData();
            for (Method method : data.getClass().getMethods()) {
                if (method.getName().equals("set") && method.getParameterCount() == 2) {
                    method.invoke(data, accessor, health);
                    if (LOGGED.compareAndSet(false, true)) {
                        FinalJudgment.LOGGER.info("[FJ] SynchedEntityDataWriter: 走 set() 写血量（accessor id={}）",
                                HealthField.accessorId());
                    }
                    return true;
                }
            }
        } catch (Throwable t) {
            if (LOGGED.compareAndSet(false, true)) {
                FinalJudgment.LOGGER.warn("[FJ] SynchedEntityDataWriter 写血量失败: {}", t.toString());
            }
        }
        return false;
    }
}
