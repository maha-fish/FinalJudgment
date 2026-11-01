package com.maha_fish.finaljudgment;

import com.maha_fish.finaljudgment.attack.AntiKillBypass;
import com.maha_fish.finaljudgment.attack.SpawnVeto;
import com.maha_fish.finaljudgment.guard.ClassRestorer;
import com.maha_fish.finaljudgment.guard.SelfGuard;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 接线自检：启动后打印"每个功能是否真的接线"。
 *
 * <p>起因：本项目多次出现"代码写了但没注册/Mixin 没进 json"的静默失效，
 * 这里集中输出一次状态，便于一眼发现问题。</p>
 */
public final class WiringAudit {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private WiringAudit() {
    }

    /** 由防御 tick 调用一次。 */
    public static void touch() {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n[FJ] ===== 接线自检 =====");
            sb.append("\n[FJ] 网络包       : ").append(NetworkHandler.channel() != null ? "已注册（ID 0-3）" : "未注册");
            sb.append("\n[FJ] 自保护       : ").append(SelfGuard.baselines() > 0 ? "基线 " + SelfGuard.baselines() + " 个类" : "未建立基线");
            sb.append("\n[FJ] 类还原基线   : ").append(ClassRestorer.baselines()).append(" 条");
            sb.append("\n[FJ] 重生黑名单   : ").append(SpawnVeto.size()).append(" 条（击杀后增长）");
            sb.append("\n[FJ] 击杀增强     : ").append(AntiKillBypass.class.getSimpleName()).append(" 已加载");
            com.maha_fish.finaljudgment.guard.NativeAntiAnalysis.scan();
            try {
                com.maha_fish.finaljudgment.guard.FallbackMatrix.report();
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                com.maha_fish.finaljudgment.guard.DefenseScanner.scan();
            } catch (Throwable ignored) {
                // 忽略
            }
            sb.append("\n[FJ] 反分析       : 可疑项 ").append(com.maha_fish.finaljudgment.guard.NativeAntiAnalysis.suspicious()).append(" 个");
            sb.append("\n[FJ] 防守扫描     : 可疑防御类 ").append(com.maha_fish.finaljudgment.guard.DefenseScanner.suspectCount()).append(" 个");
            sb.append("\n[FJ] 方法重定向   : ").append(com.maha_fish.finaljudgment.guard.MethodRedirect.enabled()
                    ? "已启用（" + com.maha_fish.finaljudgment.guard.MethodRedirect.redirects() + " 个）" : "关闭（-Dfj.methodRedirect=true 开启）");
            sb.append("\n[FJ] 周期重应用   : ").append(com.maha_fish.finaljudgment.guard.PeriodicRetransformer.rounds()).append(" 轮");
            sb.append("\n[FJ] Mixin 计数   : 公共 18 条 / 客户端 4 条（见 finaljudgment.mixins.json）");
            sb.append("\n[FJ] ====================");
            FinalJudgment.LOGGER.info(sb.toString());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 接线自检异常: {}", t.toString());
        }
    }
}