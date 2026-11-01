package com.maha_fish.finaljudgment.defense;

/**
 * 调用者上下文（扩展 7-11 的白名单机制）。
 *
 * <p>隐藏持有者的判定会作用于"外部调用者"，但<b>不能</b>影响我们自己的实体查询
 * （否则自己也选不中持有者）。因此我方查询入口用 {@link #enter()}/{@link #exit()}
 * 包一层，隐藏逻辑在 {@link #isSelf()} 为 true 时放行。</p>
 */
public final class SelfContext {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private SelfContext() {
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

    /** 该实体是否应对外部调用者隐藏。 */
    public static boolean shouldHide(net.minecraft.world.entity.Entity entity) {
        if (entity == null || isSelf()) {
            return false;
        }
        try {
            return com.maha_fish.finaljudgment.KillFlagBridge.getProtectedPlayer() == entity;
        } catch (Throwable t) {
            return false;
        }
    }
}