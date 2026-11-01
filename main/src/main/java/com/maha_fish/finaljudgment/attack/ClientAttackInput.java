package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.InputEvent;

/**
 * 客户端输入 → 选取请求（Unit 9a）。
 *
 * <p>{@code InteractionKeyMappingTriggered} 在**原版处理之前**触发，能覆盖
 * "对空气点击"的情况（`LeftClickEmpty` 只在没有目标时触发，但拿不到服务端实体表）。</p>
 *
 * <p><b>不取消事件</b>：原版行为完全保留，我们只额外发一条选取请求。</p>
 */
@Mod.EventBusSubscriber(modid = com.maha_fish.finaljudgment.common.Constants.MOD_ID, value = Dist.CLIENT)
public final class ClientAttackInput {

    private ClientAttackInput() {
    }

    private static int tickCounter;
    /** 右键远程选取的节流时间戳（ms）。 */
    private static final long[] REMOTE_HOLDER = new long[1];
    private static final java.util.concurrent.atomic.AtomicBoolean REMOTE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static boolean cooldown(long[] holder) {
        long now = System.currentTimeMillis();
        if (now - holder[0] < com.maha_fish.finaljudgment.common.Constants.ATTACK_COOLDOWN_MS) {
            return false;
        }
        holder[0] = now;
        return true;
    }
    /** 最近一次真实攻击动作的时间（毫秒）。心跳只在它之后的窗口内发送。 */
    private static long lastAttackAt;
    /** 攻击意图窗口：1 秒内有过攻击动作才算"正在攻击"。 */
    private static final long INTENT_WINDOW_MS = 1000L;

    /**
     * 每 2 tick 发一次心跳。
     *
     * <p><b>安全阀</b>：只有"主手是锚点武器" <b>且</b> "1 秒内有过真实攻击动作"才发 ——
     * 否则仅仅拿着武器站着也会被服务端当成"正在攻击"，导致身边的怪被动自动死亡（实测踩到）。</p>
     */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % com.maha_fish.finaljudgment.attack.ServerAutoAttack.REQUEST_INTERVAL_TICKS != 0) {
            return;
        }
        try {
            Player player = Minecraft.getInstance().player;

            // ② 心跳：只有 1 秒内有过真实攻击动作才发（避免站着也自动补刀）
            if (System.currentTimeMillis() - lastAttackAt > INTENT_WINDOW_MS) {
                return;
            }
            if (player != null && com.maha_fish.finaljudgment.core.T0Anchor.hasAnchorInMainHand(player)) {
                // 接线：朝向守卫 + 每 tick 已处理集合清空（防重复攻击）
                com.maha_fish.finaljudgment.core.ClientRotationGuard.tick(player);
                com.maha_fish.finaljudgment.core.AttackExecutor.beginTick(player.level().getGameTime());
                // 右键远程：直接读按键状态（不依赖 InteractionKeyMappingTriggered）
                if (Minecraft.getInstance().options.keyUse.isDown() && cooldown(REMOTE_HOLDER)) {
                    if (REMOTE_LOGGED.compareAndSet(false, true)) {
                        FinalJudgment.LOGGER.info("[FJ] 右键远程选取已启用（tick 驱动，64 格）");
                    }
                    AttackPickPacket.send((byte) 2);
                }
            }
            if (player == null || !com.maha_fish.finaljudgment.core.T0Anchor.hasAnchorInMainHand(player)) {
                return;
            }

            AttackRequestPacket.send();
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 心跳 tick 异常: {}", t.toString());
        }
    }

    private static int poseTick;

    /**
     * 右键举剑姿态现在由 {@code FinalJudgmentItemClient#getArmPose} 提供（纯渲染），
     * 这里只保留一个轻量自检钩子，<b>不再调用 startUsingItem</b>
     * （那会让玩家进入 using 状态：移动减速 + 使用动画，与"仅视觉"不符）。
     */
    @SubscribeEvent
    public static void enforceBlockPose(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        poseTick++;
    }

    /** 最底层鼠标入口：右键按下立刻发一次远程选取（不依赖任何 Forge 交互事件）。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        try {
            if (event.getButton() != 1 || event.getAction() != 1) {
                return;
            }
            Player player = Minecraft.getInstance().player;
            if (player == null || Minecraft.getInstance().screen != null) {
                return;
            }
            if (!com.maha_fish.finaljudgment.core.T0Anchor.hasAnchorInMainHand(player)) {
                return;
            }
            if (REMOTE_LOGGED.compareAndSet(false, true)) {
                FinalJudgment.LOGGER.info("[FJ] 右键远程选取已启用（鼠标事件驱动，64 格）");
            }
            AttackPickPacket.send((byte) 2);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 鼠标事件处理异常: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        try {
            Player player = Minecraft.getInstance().player;
            if (player == null || Minecraft.getInstance().screen != null) {
                return;
            }
            if (event.isAttack()) {
                lastAttackAt = System.currentTimeMillis();   // 记录攻击意图（心跳窗口依据）
                // Shift+左键 → 无限距离；普通左键 → 是否发近战请求由服务端再判
                AttackPickPacket.send((byte) (player.isShiftKeyDown() ? 1 : 0));
            } else if (event.isUseItem()) {
                // 右键 → 远程（64 格）
                AttackPickPacket.send((byte) 2);
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 输入事件处理异常: {}", t.toString());
        }
    }
}
