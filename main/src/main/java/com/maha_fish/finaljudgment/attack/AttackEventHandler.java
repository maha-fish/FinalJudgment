package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 攻击事件入口（7.5 功能 2，规范 14/32）。
 *
 * <p><b>硬约束</b>：全部 {@code HIGHEST + receiveCanceled = true}；**内部绝不读 isCanceled**。
 * 4 个事件各自行为不同，统一转发到 {@link AttackHandler}。</p>
 *
 * <p>9a 只观测不拦截：<b>不取消事件</b>，保证原版战斗行为不受影响。</p>
 */
public final class AttackEventHandler {

    /** 由 Forge 事件总线实例化。 */
    public AttackEventHandler() {
    }

    public static void init() {
        FinalJudgment.LOGGER.info(
                "[FJ] AttackEventHandler 已注册: LeftClickEmpty / LeftClickBlock / RightClickItem / RightClickBlock / EntityInteract / AttackEntityEvent");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event == null) {
            return;
        }
        try {
            AttackHandler.onLeftClickEmpty(event.getEntity());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] LeftClickEmpty 处理异常: {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event == null) {
            return;
        }
        try {
            net.minecraft.world.entity.player.Player lp = event.getEntity();
            if (lp != null && lp.isShiftKeyDown()) {
                AttackHandler.onRemote(lp);        // Shift+左键 = 远程
            } else {
                AttackHandler.onLeftClickBlock(lp);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] LeftClickBlock 处理异常: {}", t.toString());
        }
    }

    /** 右键空气：原始版本的远程入口（服务端直接射线选取，不走自定义包）。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event == null) {
            return;
        }
        try {
            AttackHandler.onRangeAttack(event.getEntity());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] RightClickItem 处理异常: {}", t.toString());
        }
    }

    /** 右键方块：同样是远程入口（很多情况下玩家对着地面/墙体右键）。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event == null) {
            return;
        }
        try {
            AttackHandler.onRangeAttack(event.getEntity());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] RightClickBlock 处理异常: {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event == null) {
            return;
        }
        try {
            Player player = event.getEntity();
            // 右键一律走远程（含右键点到实体）：此前这里走 onEntityInteract → 变成近战/范围攻击
            AttackHandler.onRangeAttack(player);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] EntityInteract 处理异常: {}", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onAttackEntity(AttackEntityEvent event) {
        if (event == null) {
            return;
        }
        try {
            if (event.getTarget() instanceof LivingEntity target) {
                AttackHandler.onAttackEntity(event.getEntity(), target);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] AttackEntityEvent 处理异常: {}", t.toString());
        }
    }
}
