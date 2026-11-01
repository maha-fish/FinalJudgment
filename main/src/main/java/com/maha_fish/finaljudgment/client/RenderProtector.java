package com.maha_fish.finaljudgment.client;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染保护（7.7 功能 3，Unit 11b）。
 *
 * <p>目标被登记为"待渲染保护"后，客户端每 tick 清掉它的 hurt/death 计时，
 * 避免出现"红色抖动/倒地不消失"的客户端残留表现。</p>
 */
@Mod.EventBusSubscriber(modid = com.maha_fish.finaljudgment.common.Constants.MOD_ID, value = Dist.CLIENT)
public final class RenderProtector {

    private static final java.util.Map<UUID, Long> PROTECTED = new ConcurrentHashMap<>();

    private RenderProtector() {
    }

    /** 保护一段时间（毫秒）。 */
    public static void protect(Entity entity, long millis) {
        if (entity != null) {
            PROTECTED.put(entity.getUUID(), System.currentTimeMillis() + millis);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PROTECTED.isEmpty()) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (UUID id : PROTECTED.keySet()) {
                Long until = PROTECTED.get(id);
                if (until == null || until < now) {
                    PROTECTED.remove(id);
                    continue;
                }
                for (Entity entity : minecraft.level.entitiesForRendering()) {
                    if (entity.getUUID().equals(id)) {
                        entity.invulnerableTime = 0;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 渲染保护异常: {}", t.toString());
        }
    }

    public static int size() {
        return PROTECTED.size();
    }
}
