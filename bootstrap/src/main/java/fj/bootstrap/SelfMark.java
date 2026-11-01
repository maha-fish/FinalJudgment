package fj.bootstrap;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨 ClassLoader 的我方 retransform 标记（规范 44 的第二级载体）。
 *
 * <p>Agent / Coremod 用 {@code Class.forName("fj.bootstrap.SelfMark", false, null)} 拿到本类，
 * 直接对 {@link #COUNT} 做 {@code incrementAndGet()} / {@code decrementAndGet()}。</p>
 *
 * <p>保持<b>零方法</b>：一旦有方法体就不再满足 2.3 的"只放常量和基础类型"。
 * 计数器语义（不是布尔）由调用方保证成对调用。</p>
 */
public final class SelfMark {

    /** 我方 retransform 计数（&gt; 0 表示当前处于我方发起的重转换中）。 */
    public static final AtomicInteger COUNT = new AtomicInteger();

    private SelfMark() {
    }
}
