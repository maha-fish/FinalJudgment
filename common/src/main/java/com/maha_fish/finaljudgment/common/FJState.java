package com.maha_fish.finaljudgment.common;

/**
 * 各层统一状态查询接口（3.2 功能 3）。
 *
 * <p>Native / Agent / Coremod / Bootstrap 四层都通过本接口被统一查询，
 * 语义逐条继承 Unit 0b 冻结的四层查询契约：</p>
 *
 * <ul>
 *   <li><b>只读</b>：不产生副作用；</li>
 *   <li><b>幂等</b>：任意时刻多次调用结果一致；</li>
 *   <li><b>绝不抛异常</b>（规范 40 / 规范 46：任一层失败不阻塞其他层）；</li>
 *   <li><b>绝不触发类初始化</b>：实现方若用 {@code Class.forName}，必须显式传
 *       {@code false, null}（规范 23）。</li>
 * </ul>
 *
 * <p>厂商实现类由各层自己提供（Native 对应 6.x、Agent 对应 4.x、Coremod 对应 5.x、
 * Bootstrap 对应 6.2 功能 10），本接口不定义任何具体层。</p>
 */
public interface FJState {

    /**
     * 本层是否就绪。
     *
     * @return true 表示本层已完成初始化且可用；任何失败路径都必须返回 false 而不是抛异常
     */
    boolean isReady();

    /**
     * 本层显示名，必须是 {@link Constants#LAYER_ORDER} 中的一员
     * （{@code Native} / {@code Agent} / {@code Coremod} / {@code Bootstrap}）。
     *
     * @return 层显示名
     */
    String getName();
}
