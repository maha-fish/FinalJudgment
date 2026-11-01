package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 击杀增强工具（移植自 ArcaneVortex 的 SetAllBooleanField / FindSetterMethod 与
 * Super Steve 的 killEntity 收尾逻辑）。
 *
 * <p>解决的问题：</p>
 * <ul>
 *   <li><b>自定义免疫标记</b>：某些 mod 用私有 boolean 字段做"无敌/受保护/已死亡"判断，
 *       {@link #setAllBooleanFields} 会把这些字段统一置位，绕开它们的判断</li>
 *   <li><b>只重写 getter 的实体</b>：{@link #forceSetHealthBySetter} 优先调用对方的
 *       {@code setHealth} setter（而不是直接写字段），能穿过"只重写 getHealth"的实现</li>
 *   <li><b>末影龙</b>：删掉实体后必须通知 {@code EndDragonFight}，否则会留下无敌龙状态</li>
 *   <li><b>multipart 实体</b>：钢铁守护者/九头蛇这类多部件实体，需要把部件 id 一并从
 *       {@code ServerLevel.partEntities} 移除</li>
 *   <li><b>顽固实体</b>：最后把位置挪到极远处，强制区块卸载</li>
 * </ul>
 */
public final class AntiKillBypass {

    private static final AtomicBoolean BOOL_LOG = new AtomicBoolean(false);
    private static final AtomicBoolean PART_LOG = new AtomicBoolean(false);

    private AntiKillBypass() {
    }

    /**
     * 5 路血量灌注（对齐 ArcaneVortex 的 HealthModifyHelp + Core/Agent 双保险）。
     *
     * <p>路线：</p>
     * <ol>
     *   <li>反射调用实体的 {@code setHealth} setter（穿过只重写 getter 的实现）</li>
     *   <li>直接写 {@code health} 字段（穿过完全覆写 setter 的实现）</li>
     *   <li>NBT：save → 改 Health → load（穿过跟随 NBT 的实现）</li>
     *   <li>MAX_HEALTH 属性 baseValue 归零（穿过读属性的实现）</li>
     *   <li>原版 {@code LivingEntity#setHealth} 兜底</li>
     * </ol>
     */
    public static void forceSetHealth5Ways(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        int ok = 0;
        // ① setter
        try {
            Method setter = findByArity(living.getClass(), "setHealth", "m_21153_", 1);
            if (setter != null) {
                setter.setAccessible(true);
                setter.invoke(living, 0.0F);
                ok++;
            }
        } catch (Throwable ignored) {
            // 下一路
        }
        // ② 直接写 health 字段
        try {
            long offset = com.maha_fish.finaljudgment.core.FieldWriter.offsetOf(
                    LivingEntity.class, new String[]{"health", "f_20919_"}, "float");
            if (offset >= 0) {
                com.maha_fish.finaljudgment.core.FieldWriter.writeFloat(living, offset, 0.0F);
                ok++;
            }
        } catch (Throwable ignored) {
            // 下一路
        }
        // ③ NBT save → 改 Health → load
        try {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            living.saveWithoutId(tag);
            tag.putFloat("Health", 0.0F);
            living.load(tag);
            ok++;
        } catch (Throwable ignored) {
            // 下一路
        }
        // ④ MAX_HEALTH 属性归零
        try {
            var maxHealth = living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(0.0D);
                ok++;
            }
        } catch (Throwable ignored) {
            // 下一路
        }
        // ⑤ 原版兜底
        try {
            living.setHealth(0.0F);
            ok++;
        } catch (Throwable ignored) {
            // 忽略
        }
        if (ok > 0 && HEALTH_LOG.compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 血量灌注成功路线数 = {}（{}）", ok, living.getType());
        }
    }

    private static final AtomicBoolean HEALTH_LOG = new AtomicBoolean(false);

    /** 把实体的 boolean 字段全部清标记（移植 ArcaneVortex SetAllBooleanField）。 */
    public static void setAllBooleanFields(Entity entity) {
        if (entity == null) {
            return;
        }
        int flipped = 0;
        try {
            for (Class<?> type = entity.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() != boolean.class || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        if (field.getBoolean(entity)) {
                            field.setBoolean(entity, false);
                            flipped++;
                        }
                    } catch (Throwable ignored) {
                        // 单个字段失败不影响其他
                    }
                }
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] setAllBooleanFields 异常: {}", t.toString());
        }
        if (flipped > 0 && BOOL_LOG.compareAndSet(false, true)) {
            FinalJudgment.LOGGER.info("[FJ] 已清除 {} 个 boolean 免疫标记（{}）", flipped, entity.getType());
        }
    }

    /** 优先调用实体的 setHealth setter，失败再直接写字段。 */
    public static void forceSetHealthBySetter(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        Method setter = findByArity(living.getClass(), "setHealth", "m_21153_", 1);
        if (setter != null) {
            try {
                setter.setAccessible(true);
                setter.invoke(living, 0.0F);
                return;
            } catch (Throwable ignored) {
                // 回退到字段写
            }
        }
        try {
            living.setHealth(0.0F);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 末影龙：通知 EndDragonFight，避免残留无敌龙状态。 */
    public static void fixDragon(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        if (!(entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon)) {
            return;
        }
        try {
            Object fight = findByArity(level.getClass(), "getDragonFight", "m_8586_", 0) != null
                    ? invoke(level, "getDragonFight", "m_8586_") : null;
            if (fight == null) {
                return;
            }
            Method killed = findMethod(fight.getClass(), "setDragonKilled", "m_64085_",
                    net.minecraft.world.entity.boss.enderdragon.EnderDragon.class);
            if (killed == null) {
                for (Method m : fight.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(dragon)) {
                        killed = m;
                        break;
                    }
                }
            }
            if (killed != null) {
                killed.setAccessible(true);
                killed.invoke(fight, dragon);
                FinalJudgment.LOGGER.info("[FJ] 已通知 EndDragonFight（末影龙）");
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] fixDragon 失败: {}", t.toString());
        }
    }

    /** multipart 实体：把部件 id 从 ServerLevel.partEntities 移除。 */
    public static void removeParts(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        try {
            if (!entity.isMultipartEntity()) {
                return;
            }
            Object map = readField(level, "partEntities", "f_143247_");
            if (!(map instanceof Map<?, ?> raw)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> parts = (Map<Object, Object>) raw;
            int removed = 0;
            for (net.minecraftforge.entity.PartEntity<?> part : entity.getParts()) {
                if (part != null && parts.remove(part.getId()) != null) {
                    removed++;
                }
            }
            if (removed > 0 && PART_LOG.compareAndSet(false, true)) {
                FinalJudgment.LOGGER.info("[FJ] 已清理 {} 个 multipart 部件（{}）", removed, entity.getType());
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] removeParts 失败: {}", t.toString());
        }
    }

    /** 顽固实体：挪到极远处，强制其所在区块被卸载。 */
    public static void moveAway(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            double y = entity.getY();
            entity.setPos(entity.getX(), y - 1024.0D, entity.getZ());
            // 双端同步：客户端也挪走，避免幽灵停在原位
            if (entity.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.maha_fish.finaljudgment.network.SyncSender.syncPos(serverLevel, entity,
                        entity.getX(), y - 1024.0D, entity.getZ());
            }
            Method raw = findByArity(entity.getClass(), "setPosRaw", "m_20349_", 3);
            if (raw != null) {
                raw.setAccessible(true);
                raw.invoke(entity, entity.getX(), y - 1024.0D, entity.getZ());
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static Method findMethod(Class<?> type, String mcpName, String srgName, Class<?>... params) {
        for (String name : new String[]{mcpName, srgName}) {
            Method found = findMethodByName(type, name, params.length);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 按"参数个数"查找方法（MCP 名与 SRG 名都试）。 */
    private static Method findByArity(Class<?> type, String mcpName, String srgName, int paramCount) {
        Method found = findMethodByName(type, mcpName, paramCount);
        return found != null ? found : findMethodByName(type, srgName, paramCount);
    }

    private static Method findMethodByName(Class<?> type, String name, int paramCount) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Object invoke(Object owner, String mcpName, String srgName) {
        for (String name : new String[]{mcpName, srgName}) {
            try {
                Method method = owner.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(owner);
            } catch (Throwable ignored) {
                // 下一个名字
            }
        }
        return null;
    }

    private static Object readField(Object owner, String mcpName, String srgName) {
        for (String name : new String[]{mcpName, srgName}) {
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
        return null;
    }
}