package com.maha_fish.finaljudgment.defense;

/**
 * Deception 层调用者上下文（扩展 7-9）。
 *
 * <p>同一份状态对"我方"与"外部"呈现不同值：我方看到真实值，外部看到诱饵值。
 * 用 ThreadLocal 深度计数标记"当前线程正在我方代码里"。</p>
 */
public final class DeceptionContext {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private DeceptionContext() {
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public static boolean isSelf() {
        return DEPTH.get()[0] > 0;
    }
}