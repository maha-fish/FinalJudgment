package com.maha_fish.finaljudgment.coremod;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SRG 候选名的跨 ClassLoader 桥（规范 20/21：不打包 common，只反射取字符串）。
 *
 * <p><b>跨 CL 只能传 String[]/String</b>：common 与 coremod 各有各的 ASM，传 ClassNode 会 ClassCastException。
 * 所以候选名反射取自 common 的 {@code SRGNames}，四级回退的解析逻辑在本地实现。</p>
 */
public final class SrgBridge {

    private static final String SRG_NAMES_CLASS = "com.maha_fish.finaljudgment.common.SRGNames";
    /** 系统属性桥前缀：主 mod 在构造器阶段写入 fj.srg.&lt;KEY&gt; = "a|b|c" */
    public static final String PROPERTY_PREFIX = "fj.srg.";

    /**
     * Coremod 本地候选表（仅覆盖本模块实际注入的 9 个键）。
     *
     * <p><b>为什么必须有这一份</b>：MC 的 Entity/LivingEntity 在<b>主 mod 之前</b>就被变换，
     * 此刻 transformer 的 context CL 看不到 mod 类，属性桥也还没来得及写入 ——
     * 反射与属性两条路在时序上都必然失败（实测日志：ClassNotFoundException: SRGNames）。
     * 因此 coremod 自持一份最小副本；跨层一致性由 Unit 4 的冒烟（逐字段比对）与本节注释共同保证。</p>
     */
    private static final Map<String, String[]> LOCAL = Map.of(
            "ENTITY_SET_REMOVED", new String[]{"setRemoved", "m_142467_"},
            "ENTITY_REMOVE", new String[]{"remove", "m_142687_"},
            "ENTITY_DISCARD", new String[]{"discard", "m_146870_"},
            "ENTITY_TICK", new String[]{"tick", "m_8119_"},
            "LIVING_ENTITY_SET_HEALTH", new String[]{"setHealth", "m_21153_"},
            "LIVING_ENTITY_HURT", new String[]{"hurt", "m_6469_"},
            "LIVING_ENTITY_KILL", new String[]{"kill", "m_6074_"},
            "LIVING_ENTITY_DIE", new String[]{"die", "m_6667_"},
            "PLAYER_DROP_EQUIPMENT", new String[]{"dropEquipment", "m_36232_"});
    private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean diagDone;

    private SrgBridge() {
    }

    /**
     * 取 SRG 候选名。
     *
     * <p><b>第一级：系统属性桥</b> —— 主 mod 在构造器阶段把候选表写进
     * {@code fj.srg.<KEY>}（值形如 {@code setRemoved|m_142467_}）。这条路径完全不涉及
     * ClassLoader，绕开"transformer 里的 context CL 看不到 mod 类"的层级隔离问题。</p>
     *
     * <p>第二级：反射 common 的 {@code SRGNames}；第三级：返回 null（调用方跳过注入）。</p>
     */
    public static String[] candidates(ClassLoader loader, String fieldKey) {
        String[] cached = CACHE.get(fieldKey);
        if (cached != null) {
            return cached;
        }
        // 第 0 级（优先）：coremod 本地候选表 —— 唯一在"MC 类早于主 mod 加载"时序下可靠的来源
        String[] local = LOCAL.get(fieldKey);
        if (local != null) {
            CACHE.put(fieldKey, local);
            return local;
        }
        // 第一级：系统属性桥
        String packed = System.getProperty(PROPERTY_PREFIX + fieldKey);
        if (packed != null && !packed.isEmpty()) {
            String[] parts = packed.split("\\|");
            CACHE.put(fieldKey, parts);
            return parts;
        }
        // 第二级：反射
        try {
            Class<?> holder = Class.forName(SRG_NAMES_CLASS, false, loader);
            String[] value = (String[]) holder.getField(fieldKey).get(null);
            if (value != null && value.length > 0) {
                CACHE.put(fieldKey, value);
                return value;
            }
        } catch (Throwable t) {
            CoremodLog.info("SRG 候选反射失败 " + fieldKey + ": " + t);
        }
        return null;
    }

    /** 四级回退：名字+描述符 → 只名字 → 只描述符 → 关键字模糊。 */
    public static String resolveField(ClassNode owner, String[] candidates, String desc) {
        if (owner == null || owner.fields == null || candidates == null) {
            return null;
        }
        String byNameAndDesc = null;
        String byName = null;
        String byDesc = null;
        for (FieldNode field : owner.fields) {
            if (field == null || field.name == null) {
                continue;
            }
            boolean nameHit = matches(field.name, candidates);
            boolean descHit = desc == null || desc.equals(field.desc);
            if (nameHit && descHit && byNameAndDesc == null) {
                byNameAndDesc = field.name;
            }
            if (nameHit && byName == null) {
                byName = field.name;
            }
            if (descHit && byDesc == null) {
                byDesc = field.name;
            }
        }
        String found = byNameAndDesc != null ? byNameAndDesc : (byName != null ? byName : byDesc);
        if (found == null) {
            found = fuzzy(owner, candidates, desc);
        }
        diagnose(owner, candidates, desc, found);
        return found;
    }

    private static boolean matches(String actual, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate != null && candidate.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static String fuzzy(ClassNode owner, String[] candidates, String desc) {
        String digits = null;
        for (String candidate : candidates) {
            if (candidate != null && candidate.length() > 3 && candidate.charAt(0) == 'f' && candidate.charAt(1) == '_') {
                String token = candidate.substring(2);
                if (token.endsWith("_")) {
                    token = token.substring(0, token.length() - 1);
                }
                digits = token;
                break;
            }
        }
        for (FieldNode field : owner.fields) {
            if (field == null || field.name == null) {
                continue;
            }
            if (field.name.toLowerCase(Locale.ROOT).contains("removal")) {
                return field.name;
            }
            if (digits != null && field.name.contains(digits)) {
                return field.name;
            }
            if (desc != null && desc.contains("RemovalReason") && desc.equals(field.desc)) {
                return field.name;
            }
        }
        return null;
    }

    private static void diagnose(ClassNode owner, String[] candidates, String desc, String found) {
        if (diagDone) {
            return;
        }
        diagDone = true;
        String ownerName = owner == null ? "null" : owner.name;
        if (found != null) {
            CoremodLog.info("字段解析成功 owner=" + ownerName + " 候选=" + Arrays.toString(candidates)
                    + " desc=" + desc + " 命中=" + found);
            return;
        }
        StringBuilder sb = new StringBuilder("找不到字段 owner=").append(ownerName)
                .append(" 候选=").append(Arrays.toString(candidates))
                .append(" desc=").append(desc).append(" 目标类全部字段=[");
        if (owner != null && owner.fields != null) {
            for (int i = 0; i < owner.fields.size(); i++) {
                FieldNode field = owner.fields.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(field == null ? "null" : field.name + " : " + field.desc);
            }
        }
        CoremodLog.warn(sb.append(']').toString());
    }
}
