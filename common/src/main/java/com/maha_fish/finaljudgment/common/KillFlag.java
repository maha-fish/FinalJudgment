package com.maha_fish.finaljudgment.common;

/**
 * 跨层通信开关（3.2 功能 4）。
 *
 * <p>Coremod 生成的字节码直接 {@code GETSTATIC} 读这两个字段，不经过任何方法调用，
 * 因此对方 hook 任何 Java 方法都无效。语义是 <b>引用相等</b>：</p>
 *
 * <ul>
 *   <li>{@code null} 时任何 {@code this} 都不等于它 → 守卫条件自动跳过，无需额外判空逻辑；</li>
 *   <li>比较必须是 {@code ==}（引用相等），<b>禁止</b> {@code equals}。</li>
 * </ul>
 *
 * <p><b>唯一加载约束（规范 21 / 10.0.1）：</b>本类必须只被加载一份。Coremod 的构建配置
 * 不能打包 {@code common} 包，否则注入的 {@code GETSTATIC} 会读到另一个副本的 {@code null}，
 * 表现为「守卫永远不触发」（7.13 现象 4），或直接 {@code NoClassDefFoundError}。</p>
 *
 * <p><b>已知缺陷（原文保留，MVP 可用）：</b>{@link #targetEntity} 是单值，AOE 攻击多目标时
 * 后者覆盖前者。改进方向见 3.2 功能 4（{@code Set<Object>} + {@code WeakHashMap}），
 * 不在 Unit 1 范围内。</p>
 */
public final class KillFlag {

    private KillFlag() {
    }

    /** 攻击目标（Coremod 守卫用 {@code this == targetEntity} 判定）。 */
    public static volatile Object targetEntity;

    /** 防御目标（持有者；Coremod 守卫用 {@code this == protectedPlayer} 判定）。 */
    public static volatile Object protectedPlayer;
}
