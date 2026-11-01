package com.maha_fish.finaljudgment.coremod;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 主 transformer（5.2 功能 2）：按目标类名分派到 {@link Patches}。
 * ASM 由 ModLauncher 提供（compileOnly），只用 9.5 已存在的 API（规范 28）。
 */
public final class CoremodTransformer implements ITransformer<ClassNode> {

    private static final String ENTITY = "net.minecraft.world.entity.Entity";
    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";
    private static final String SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer";
    private static final String PLAYER = "net.minecraft.world.entity.player.Player";
    private static final String SERVER_LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";

    /** 扩展 4-2：正则扩展目标（按类名匹配）。 */
    private static final java.util.List<String> EXTRA_PATTERNS = new java.util.ArrayList<>();

    private static final Set<Target> TARGETS = buildTargets();

    private static Set<Target> buildTargets() {
        Set<Target> targets = new LinkedHashSet<>();
        targets.add(Target.targetClass(ENTITY));
        targets.add(Target.targetClass(LIVING_ENTITY));
        targets.add(Target.targetClass(SERVER_PLAYER));
        // dropEquipment() 在 Player 上（ServerPlayer 未覆写）→ 必须单独列为目标
        targets.add(Target.targetClass(PLAYER));
        targets.add(Target.targetClass(SERVER_LEVEL));
        // Minecraft 只存在于客户端；服务端该类永不被加载，因此列在这里也不会误注入（规范 25）
        targets.add(Target.targetClass(MINECRAFT));
        // 扩展 4-2：正则扩展目标（-Dfj.coremod.extraTargets=.*Boss.*,com.foo.Bar）
        try {
            String extra = System.getProperty("fj.coremod.extraTargets", "").trim();
            if (!extra.isEmpty()) {
                for (String pattern : extra.split(",")) {
                    String p = pattern.trim();
                    if (p.isEmpty()) {
                        continue;
                    }
                    CoremodLog.info("扩展目标正则已注册: " + p);
                    EXTRA_PATTERNS.add(p);
                }
            }
        } catch (Throwable t) {
            CoremodLog.warn("扩展目标注册失败: " + t);
        }
        return targets;
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        // 诊断探针（不依赖日志通道，主 mod 直接读这两个系统属性）
        System.setProperty("fj.coremod.transform.hits",
                Integer.toString(Integer.getInteger("fj.coremod.transform.hits", 0) + 1));
        System.setProperty("fj.coremod.transform.last", String.valueOf(input.name));

        // 开关语义：**默认开启**，只有显式设为 false 才关闭（生产环境没有任何 JVM 参数，
        // 若按"默认关闭"会把所有守卫静默关掉 —— 这正是 dev 能跑、生产失效的经典陷阱）。
        if (!enabled("fj.coremod.patch")) {
            return input;
        }
        boolean patchEntity = enabled("fj.coremod.patch.entity");
        boolean patchLiving = enabled("fj.coremod.patch.livingentity");
        boolean patchPlayer = enabled("fj.coremod.patch.serverplayer");
        boolean patchLevel = enabled("fj.coremod.patch.serverlevel");

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = CoremodTransformer.class.getClassLoader();
        }
        try {
            boolean changed = false;
            // 注意：ASM 的 ClassNode.name 是斜杠形式（net/minecraft/...），
            // 而 Target.targetClass 用的是点号形式；这里统一转成点号再匹配
            switch (input.name.replace('/', '.')) {
                case ENTITY:
                    changed = patchEntity && Patches.entity(input, loader);
                    break;
                case LIVING_ENTITY:
                    changed = patchLiving && Patches.livingEntity(input, loader);
                    break;
                case SERVER_PLAYER:
                    changed = patchPlayer && Patches.serverPlayer(input, loader);
                    break;
                case PLAYER:
                    changed = patchPlayer && Patches.player(input, loader);
                    break;
                case SERVER_LEVEL:
                    changed = patchLevel && Patches.serverLevel(input);
                    break;
                case MINECRAFT:
                    changed = Patches.minecraftRunTick(input);
                    break;
                default:
                    if (matchesExtra(input.name.replace('/', '.'))) {
                        CoremodLog.info("正则目标命中: " + input.name);
                        changed = patchLiving && Patches.livingEntity(input, loader);
                        break;
                    }
                    return input;
            }
            if (changed) {
                CoremodLog.info("已注入: " + input.name + " (loader=" + loader.getClass().getName() + ")");
            }
        } catch (Throwable t) {
            // 失败隔离：注入失败不能拦住类加载
            CoremodLog.warn("注入失败(放行原字节): " + input.name + " (" + t + ")");
        }
        return input;
    }

    /** 默认开启：只有显式写成 "false" 才关闭。 */
    private static boolean enabled(String key) {
        return !"false".equalsIgnoreCase(System.getProperty(key, "true"));
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return TARGETS;
    }
    /** 扩展 4-2：类名是否命中正则扩展目标。 */
    private static boolean matchesExtra(String binaryName) {
        for (String pattern : EXTRA_PATTERNS) {
            try {
                if (binaryName.matches(pattern)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 非法正则忽略
            }
        }
        return false;
    }
}