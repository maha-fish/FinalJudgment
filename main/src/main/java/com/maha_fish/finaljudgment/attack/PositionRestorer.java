package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 传送后恢复（7.18 / 规范 37）。
 *
 * <p>参考示例项目 {@code core/PositionWriter} 的做法：</p>
 * <ol>
 *   <li><b>优先公开 API</b>：玩家走 {@code connection.teleport(...)}（服务端权威同步），
 *       其他实体走 {@code entity.setPos(...)}（会同时更新 position 与 boundingBox）</li>
 *   <li><b>兜底字段直写</b>：用 Unsafe 直接写 {@code Entity.position}（{@code position} / {@code f_19854_}），
 *       <b>不调用 setAccessible</b>——这正是参考项目绕开模块访问限制的关键</li>
 *   <li><b>事后校验</b>：读回 {@code entity.position()}，偏差超过容差才算失败</li>
 * </ol>
 */
public final class PositionRestorer {

    private static final AtomicInteger RESTORED = new AtomicInteger();
    private static final AtomicInteger FALLBACKS = new AtomicInteger();

    /** 位置字段（MCP 名 + SRG 名）。注意是 {@code position} 而不是 {@code pos}。 */
    private static final String[] POSITION_FIELD = {"position", "f_19854_"};

    private PositionRestorer() {
    }

    public static boolean restore(Entity entity, Vec3 position) {
        if (entity == null || position == null) {
            return false;
        }
        boolean ok = false;

        // ① 公开 API（映射无关，最稳）
        try {
            if (entity instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
                serverPlayer.connection.teleport(position.x, position.y, position.z,
                        serverPlayer.getYRot(), serverPlayer.getXRot());
                ok = true;
            } else {
                entity.setPos(position.x, position.y, position.z);
                ok = true;
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 位置恢复(API) 失败: {}", t.toString());
        }

        // ② 字段直写兜底（参考项目 PositionWriter 同款：Unsafe + objectFieldOffset）
        if (!withinTolerance(entity, position)) {
            if (forcePositionField(entity, position)) {
                ok = true;
                FALLBACKS.incrementAndGet();
            }
        }

        // ③ 校验
        if (!withinTolerance(entity, position)) {
            FinalJudgment.LOGGER.debug("[FJ] 位置恢复后仍偏差过大: 期望={} 实际={}",
                    position, entity.position());
            return false;
        }
        if (ok) {
            RESTORED.incrementAndGet();
        }
        return ok;
    }

    /** 直接写 {@code Entity.position} 字段（不 setAccessible，避免模块访问拒绝）。 */
    private static boolean forcePositionField(Entity entity, Vec3 position) {
        try {
            long offset = com.maha_fish.finaljudgment.core.FieldWriter.offsetOf(
                    Entity.class, POSITION_FIELD, "Vec3");
            if (offset < 0) {
                return false;
            }
            boolean written = com.maha_fish.finaljudgment.core.FieldWriter.writeObject(entity, offset, position);
            if (written) {
                FinalJudgment.LOGGER.debug("[FJ] 位置已改用字段直写: {}", position);
            }
            return written;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 位置字段直写失败: {}", t.toString());
            return false;
        }
    }

    /** 位置是否在容差内。 */
    private static boolean withinTolerance(Entity entity, Vec3 expected) {
        try {
            return entity.position().distanceTo(expected) <= 0.05D;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int restored() {
        return RESTORED.get();
    }

    public static int fallbacks() {
        return FALLBACKS.get();
    }
}
