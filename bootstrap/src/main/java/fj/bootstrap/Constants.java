package fj.bootstrap;

/**
 * Bootstrap ClassLoader 侧常量（2.3 Bootstrap 部分）。
 *
 * <p><b>约束（规范 23 / ENV_SPEC 4.5）：</b>本包不得引用任何
 * {@code net.minecraft.*} / {@code net.minecraftforge.*} / {@code com.maha_fish.finaljudgment.*}
 * 类型；只能引用 JDK（java.* / javax.* / jdk.*）。</p>
 *
 * <p><b>这是 common {@code com.maha_fish.finaljudgment.common.Constants} 的同值副本</b>，
 * 字段名与值必须逐字一致：Bootstrap 类一旦被注入就不可撤回，改值等于让两侧永久不一致。</p>
 */
public final class Constants {

    private Constants() {
    }

    /** mod id */
    public static final String MOD_ID = "finaljudgment";

    /** 锚点物品 NBT 键 */
    public static final String ANCHOR_NBT_KEY = "final_judgment_core";

    /** 锚点物品 NBT 值 */
    public static final String ANCHOR_NBT_VALUE = "v1";

    /** Coremod 已加载标志 */
    public static final String SYS_COREMOD_LOADED = "fj.coremod.loaded";

    /** Bootstrap 已注入标志 */
    public static final String SYS_BOOTSTRAP_LOADED = "fj.bootstrap.loaded";

    /** 反射审计开关 */
    public static final String SYS_REFLECTION_AUDIT = "fj.reflection.audit";

    /** 我方 retransform 标记（系统属性级降级载体，规范 44） */
    public static final String SYS_SELF_RETRANSFORM = "fj.self.retransform";
}
