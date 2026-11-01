package com.maha_fish.finaljudgment.common;

import java.util.Arrays;
import java.util.Locale;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * SRG 名集中管理。
 *
 * <p>对应 IMPLEMENTATION_SPEC 3.2 功能 1「SRG 名集中管理」与 3.3「完整 SRG 名对照表」。
 * <b>本类是全项目唯一允许出现 {@code f_} / {@code m_} 字面量的地方</b>（规范 1、规范 13）。</p>
 *
 * <p>跨 ClassLoader 契约（规范 20、规范 21）：Coremod 与主模块不共享静态字段，
 * Coremod 只能经<b>目标类的 ClassLoader</b> 反射读取本类的
 * {@code public static final String[]} 字段。因此<b>字段名是稳定的公共 API，不得改名</b>。
 * 值语义只有一种：{@code [0]} = Mojang 名，其余为 SRG 候选（个别成员有两个 SRG 候选）。</p>
 *
 * <p>ASM 为 compileOnly（ENV_SPEC 七 + 规范 28）：本模块只用到 ASM 9.5 已存在的
 * {@code ClassNode} / {@code FieldNode} API；运行期由调用方提供 ASM。</p>
 */
public final class SRGNames {

    private SRGNames() {
    }

    // ===================== Entity（3.3 表） =====================

    /** Entity.removalReason */
    public static final String[] ENTITY_REMOVAL_REASON = {"removalReason", "f_146795_"};

    /** Entity.position */
    public static final String[] ENTITY_POSITION = {"position", "f_19825_"};

    /** Entity.blockPosition */
    public static final String[] ENTITY_BLOCK_POSITION = {"blockPosition", "f_19826_"};

    /** Entity.tick */
    public static final String[] ENTITY_TICK = {"tick", "m_8119_"};

    /** Entity.setRemoved */
    public static final String[] ENTITY_SET_REMOVED = {"setRemoved", "m_142467_"};

    /** Entity.remove */
    public static final String[] ENTITY_REMOVE = {"remove", "m_142687_"};

    /** Entity.discard */
    public static final String[] ENTITY_DISCARD = {"discard", "m_146870_"};

    /** Entity.isRemoved */
    public static final String[] ENTITY_IS_REMOVED = {"isRemoved", "m_213877_"};

    /** Entity.isInvulnerableTo */
    public static final String[] ENTITY_IS_INVULNERABLE_TO = {"isInvulnerableTo", "m_6673_"};

    /** Entity.isInvulnerable */
    public static final String[] ENTITY_IS_INVULNERABLE = {"isInvulnerable", "m_6063_"};

    /** Entity.fireImmune */
    public static final String[] ENTITY_FIRE_IMMUNE = {"fireImmune", "m_5825_"};

    // ===================== LivingEntity（3.3 表） =====================

    /** LivingEntity.getHealth */
    public static final String[] LIVING_ENTITY_GET_HEALTH = {"getHealth", "m_21223_"};

    /** LivingEntity.setHealth */
    public static final String[] LIVING_ENTITY_SET_HEALTH = {"setHealth", "m_21153_"};

    /** LivingEntity.getMaxHealth */
    public static final String[] LIVING_ENTITY_GET_MAX_HEALTH = {"getMaxHealth", "m_21233_"};

    /** LivingEntity.hurt */
    public static final String[] LIVING_ENTITY_HURT = {"hurt", "m_6469_"};

    /** LivingEntity.die */
    public static final String[] LIVING_ENTITY_DIE = {"die", "m_6667_"};

    /** LivingEntity.kill */
    public static final String[] LIVING_ENTITY_KILL = {"kill", "m_6074_"};

    /** LivingEntity.isAlive */
    public static final String[] LIVING_ENTITY_IS_ALIVE = {"isAlive", "m_6084_"};

    /** LivingEntity.isDeadOrDying */
    public static final String[] LIVING_ENTITY_IS_DEAD_OR_DYING = {"isDeadOrDying", "m_21224_"};

    /** LivingEntity.dropAllDeathLoot */
    public static final String[] LIVING_ENTITY_DROP_ALL_DEATH_LOOT = {"dropAllDeathLoot", "m_6668_"};

    /** LivingEntity.dead */
    public static final String[] LIVING_ENTITY_DEAD = {"dead", "f_20861_"};

    // ===================== Mob / Player =====================

    /** Mob.target */
    public static final String[] MOB_TARGET = {"target", "f_21340_"};

    /** Player.dropEquipment */
    public static final String[] PLAYER_DROP_EQUIPMENT = {"dropEquipment", "m_36232_"};

    // ===================== ServerLevel / 区块索引 =====================

    /** ServerLevel.entityManager */
    public static final String[] SERVER_LEVEL_ENTITY_MANAGER = {"entityManager", "f_143244_"};

    /**
     * ServerLevel.entityTickList —— ⚠️ 最易错的一行。
     *
     * <p>SRG 名是 {@code f_143243_}，<b>不是</b> {@code f_143246_}。后者是相邻的
     * {@link #SERVER_LEVEL_NAVIGATING_MOBS}。混用会导致清 ticklist 失败，
     * 表现为「实体从 ticklist 消失但逻辑上还活着」。两者必须是两个独立常量。</p>
     */
    public static final String[] SERVER_LEVEL_ENTITY_TICK_LIST = {"entityTickList", "f_143243_"};

    /** ServerLevel.navigatingMobs（⚠️ 与 entityTickList 极易混淆） */
    public static final String[] SERVER_LEVEL_NAVIGATING_MOBS = {"navigatingMobs", "f_143246_"};

    /** ServerChunkCache.chunkMap（两个 SRG 候选） */
    public static final String[] SERVER_CHUNK_CACHE_CHUNK_MAP = {"chunkMap", "f_8325_", "f_8364_"};

    /** ChunkMap.entityMap（两个 SRG 候选） */
    public static final String[] CHUNK_MAP_ENTITY_MAP = {"entityMap", "f_140150_", "f_140166_"};

    /** Minecraft.runTick（仅客户端） */
    public static final String[] MINECRAFT_RUN_TICK = {"runTick", "m_91383_"};

    // ============ 7b：6 处集合摘除（MCP 名为实测值；SRG 名待映射表回填，未回填时靠描述符兜底）===

    /** PersistentEntitySectionManager.sectionStorage (EntitySectionStorage) */
    public static final String[] PERSISTENT_SECTION_STORAGE = {"sectionStorage", "f_157495_"};

    /** PersistentEntitySectionManager.visibleEntityStorage (EntityLookup) */
    public static final String[] PERSISTENT_VISIBLE_STORAGE = {"visibleEntityStorage", "f_157494_"};

    /** PersistentEntitySectionManager.knownUuids (Set) —— 实测名是 knownUuids，不是 knownUuid */
    public static final String[] PERSISTENT_KNOWN_UUIDS = {"knownUuids", "f_157491_"};

    /** EntityTickList.active (Int2ObjectMap) */
    public static final String[] ENTITY_TICK_LIST_ACTIVE = {"active", "f_156903_"};

    /** EntityTickList.passive (Int2ObjectMap) */
    public static final String[] ENTITY_TICK_LIST_PASSIVE = {"passive", "f_156904_"};

    /** EntitySectionStorage.sections (Long2ObjectMap) */
    public static final String[] ENTITY_SECTION_STORAGE_SECTIONS = {"sections", "f_156890_"};

    /** EntitySection.storage (ClassInstanceMultiMap) */
    public static final String[] ENTITY_SECTION_STORAGE = {"storage", "f_156827_"};

    // ============ 11b/12 补齐：结构性移除与判定所需字段（MCP + SRG 双写）============

    /** Entity.levelCallback (EntityInLevelCallback) */
    public static final String[] ENTITY_LEVEL_CALLBACK = {"levelCallback", "f_146801_"};

    /** Entity.noPhysics */
    public static final String[] ENTITY_NO_PHYSICS = {"noPhysics", "f_19794_"};

    /** Entity.passengers */
    public static final String[] ENTITY_PASSENGERS = {"passengers", "f_19823_"};

    /** Entity.boundingBox */
    public static final String[] ENTITY_BOUNDING_BOX = {"boundingBox", "f_19828_"};

    /** Entity.invulnerable */
    public static final String[] ENTITY_INVULNERABLE = {"invulnerable", "f_19815_"};

    /** Entity.invulnerableTime */
    public static final String[] ENTITY_INVULNERABLE_TIME = {"invulnerableTime", "f_19816_"};

    /** LivingEntity.deathTime */
    public static final String[] LIVING_ENTITY_DEATH_TIME = {"deathTime", "f_20919_"};

    /** EntityLookup.byUuid */
    public static final String[] ENTITY_LOOKUP_BY_UUID = {"byUuid", "f_156808_"};

    /** EntityLookup.byId */
    public static final String[] ENTITY_LOOKUP_BY_ID = {"byId", "f_156807_"};

    /** ClassInstanceMultiMap.map */
    public static final String[] CLASS_INSTANCE_MULTI_MAP = {"map", "f_13527_"};


    // ===================== 解析工具 =====================

    /** 一次性诊断标志：首次调用打印后置位，避免刷屏（3.2 功能 1）。 */
    private static volatile boolean fieldDiagDone;

    /**
     * 名字匹配：实际名字是否等于任一候选。
     *
     * @param actual     字节码里的真实名字（可能是 Mojang 名、SRG 名或中间态）
     * @param candidates 双候选表
     * @return true 表示命中
     */
    public static boolean match(String actual, String[] candidates) {
        if (actual == null || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (actual.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按描述符从 {@link ClassNode} 解析字段的真实名字，四级回退（3.2 功能 1）。
     *
     * <ol>
     *   <li>名字 + 描述符都匹配</li>
     *   <li>只名字匹配</li>
     *   <li>只描述符匹配</li>
     *   <li>关键字模糊匹配（名字含 {@code removal}、名字含候选里的 SRG 数字、描述符含 {@code RemovalReason}）</li>
     * </ol>
     *
     * <p>失败返回 {@code null}，由调用方决定「跳过该注入点」；<b>任何内部异常都转成 null + 诊断，
     * 绝不向外抛</b>（失败隔离）。</p>
     *
     * @param owner      目标类
     * @param candidates 双候选表（取自本类常量）
     * @param desc       期望字段描述符；为 null 时退化为只按名字匹配
     * @return 命中的真实字段名，找不到为 null
     */
    public static String resolveField(ClassNode owner, String[] candidates, String desc) {
        String found;
        try {
            found = resolveField0(owner, candidates, desc);
        } catch (Throwable t) {
            found = null;
            Constants.log("SRGNames.resolveField 异常: " + t);
        }
        diagnose(owner, candidates, desc, found);
        return found;
    }

    private static String resolveField0(ClassNode owner, String[] candidates, String desc) {
        if (owner == null || owner.fields == null || candidates == null || candidates.length == 0) {
            return null;
        }
        String byNameAndDesc = null;
        String byName = null;
        String byDesc = null;
        for (FieldNode field : owner.fields) {
            if (field == null || field.name == null) {
                continue;
            }
            boolean nameHit = match(field.name, candidates);
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
        if (byNameAndDesc != null) {
            return byNameAndDesc;
        }
        if (byName != null) {
            return byName;
        }
        if (byDesc != null) {
            return byDesc;
        }
        return fuzzyField(owner, candidates, desc);
    }

    /** 第四级：关键字模糊匹配。SRG 数字从候选表里提取，不在本类外的任何地方写死。 */
    private static String fuzzyField(ClassNode owner, String[] candidates, String desc) {
        String digits = srgDigits(candidates);
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

    /** 从 {@code f_146795_} 这类候选中取出纯数字片段 {@code 146795}。 */
    private static String srgDigits(String[] candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.length() < 4) {
                continue;
            }
            if (candidate.charAt(0) != 'f' || candidate.charAt(1) != '_') {
                continue;
            }
            String token = candidate.substring(2);
            if (token.endsWith("_")) {
                token = token.substring(0, token.length() - 1);
            }
            if (token.isEmpty()) {
                continue;
            }
            boolean allDigits = true;
            for (int i = 0; i < token.length(); i++) {
                if (!Character.isDigit(token.charAt(i))) {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                return token;
            }
        }
        return null;
    }

    /**
     * 一次性诊断（3.2 功能 1）：首次失败必须打印目标类的<b>全部字段名 + 描述符</b>、
     * 期望描述符、期望候选名列表。
     */
    private static void diagnose(ClassNode owner, String[] candidates, String desc, String found) {
        if (fieldDiagDone) {
            return;
        }
        fieldDiagDone = true;
        String ownerName = owner == null ? "null" : owner.name;
        if (found != null) {
            Constants.log("SRGNames: 字段解析成功 owner=" + ownerName
                    + " 期望候选=" + Arrays.toString(candidates)
                    + " 期望描述符=" + desc
                    + " 命中=" + found);
            return;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("SRGNames: 找不到字段 owner=").append(ownerName)
                .append(" 期望候选=").append(Arrays.toString(candidates))
                .append(" 期望描述符=").append(desc)
                .append(" 目标类的全部字段=[");
        if (owner != null && owner.fields != null) {
            for (int i = 0; i < owner.fields.size(); i++) {
                FieldNode field = owner.fields.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(field == null ? "null" : field.name + " : " + field.desc);
            }
        }
        sb.append(']');
        Constants.log(sb.toString());
    }
}
