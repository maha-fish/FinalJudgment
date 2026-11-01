package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端主动攻击（7.5 功能 8，step 23）。
 *
 * <p>节奏（严格按规范）：<b>攻击请求每 2 tick</b>、<b>心跳超时 10 tick</b>、<b>每 3 tick 主动扫描</b>。
 * 心跳在"收到攻击请求包/状态包"时刷新；超时后不再自动攻击。</p>
 */
public final class ServerAutoAttack {

    public static final int REQUEST_INTERVAL_TICKS = 2;
    public static final int HEARTBEAT_TIMEOUT_TICKS = 10;
    public static final int SCAN_INTERVAL_TICKS = 3;

    private static final Map<UUID, Long> HEARTBEAT = new ConcurrentHashMap<>();
    /** 每个玩家"已锁定"的目标：补刀只跟它，**绝不重新选目标**。 */
    private static final Map<UUID, UUID> COMMITTED = new ConcurrentHashMap<>();
    private static final AtomicInteger TICKS = new AtomicInteger();
    private static final AtomicInteger AUTO_KILLS = new AtomicInteger();
    private static volatile boolean enabled = true;

    private ServerAutoAttack() {
    }

    public static void init() {
        FinalJudgment.LOGGER.info("[FJ] ServerAutoAttack 已就绪（请求 {}t / 心跳 {}t / 扫描 {}t）",
                REQUEST_INTERVAL_TICKS, HEARTBEAT_TIMEOUT_TICKS, SCAN_INTERVAL_TICKS);
    }

    /** 收到攻击请求包/状态包时刷新心跳。 */
    public static void refreshHeartbeat(ServerPlayer player) {
        if (player != null) {
            HEARTBEAT.put(player.getUUID(), (long) TICKS.get());
        }
    }

    public static boolean heartbeatAlive(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        Long last = HEARTBEAT.get(player.getUUID());
        if (last == null) {
            return false;
        }
        return TICKS.get() - last <= HEARTBEAT_TIMEOUT_TICKS;
    }

    public static void tick() {
        int tick = TICKS.incrementAndGet();
        if (!enabled) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        try {
            // 每 2 tick：处理带心跳的玩家
            if (tick % REQUEST_INTERVAL_TICKS == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!heartbeatAlive(player)) {
                        continue;
                    }
                    // 每 3 tick：主动扫描最近目标并走静默击杀
                    if (tick % SCAN_INTERVAL_TICKS == 0) {
                        scanAndKill(player);
                    }
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] ServerAutoAttack tick 异常: {}", t.toString());
        }
    }

    /** 玩家真实命中某个目标时调用：锁定它，后续补刀只认这个 UUID。 */
    public static void commitTarget(ServerPlayer player, LivingEntity target) {
        if (player != null && target != null) {
            COMMITTED.put(player.getUUID(), target.getUUID());
        }
    }

    private static void scanAndKill(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        // 安全阀：主手必须是带锚点的武器，否则绝不自动攻击
        if (!com.maha_fish.finaljudgment.core.T0Anchor.hasAnchorInMainHand(player)) {
            return;
        }
        // 关键：补刀只跟"已锁定目标"，绝不重新选目标（否则会补错对象：村民、别人的宠物、旁边的怪）
        UUID committed = COMMITTED.get(player.getUUID());
        if (committed == null) {
            return;
        }
        LivingEntity target = resolveTarget(level, committed);
        if (target == null || KillValidator.isGone(target)) {
            COMMITTED.remove(player.getUUID());
            return;
        }
        if (player.distanceTo(target) > com.maha_fish.finaljudgment.common.Constants.MELEE_REACH) {
            return;                      // 距离超了就不补（不做远程补刀）
        }
        if (SilenceKillUtil.kill(level, target)) {
            AUTO_KILLS.incrementAndGet();
            FinalJudgment.LOGGER.debug("[FJ] 补刀成功（锁定目标）: {}", target.getUUID());
        }
    }

    /** 按 UUID 在当前维度里找回实体（只认锁定目标）。 */
    private static LivingEntity resolveTarget(ServerLevel level, UUID id) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof LivingEntity living && living.getUUID().equals(id)) {
                return living;
            }
        }
        return null;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static int autoKills() {
        return AUTO_KILLS.get();
    }

    public static int ticks() {
        return TICKS.get();
    }
}
