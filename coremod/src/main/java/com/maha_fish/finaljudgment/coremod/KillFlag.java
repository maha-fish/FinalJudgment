package com.maha_fish.finaljudgment.coremod;

/**
 * 跨层通信开关（**必须住在 coremod 里**）。
 *
 * <p>为什么不在 common：注入的 {@code GETSTATIC} 位于 MC 类（game 层），
 * 而 common 在 mod 层 —— game 层的 CL 解析不到 mod 层的类，运行期会
 * {@code NoClassDefFoundError}，被守卫 try-catch 吞掉后表现为
 * "生物死亡不消失、持续掉战利品"（已实测踩坑）。coremod jar 是 MODLAUNCHER，
 * 在 game 层可见，主 mod 也能看见 → 天然唯一且双方可达。</p>
 *
 * <p>主 mod 侧通过反射写入（ENV_SPEC：main 编译期不依赖 coremod），见 main 的 KillFlagBridge。</p>
 */
public final class KillFlag {

    private KillFlag() {
    }

    /** 攻击目标。 */
    public static volatile Object targetEntity;

    /** 防御目标（持有者）。 */
    public static volatile Object protectedPlayer;
}
