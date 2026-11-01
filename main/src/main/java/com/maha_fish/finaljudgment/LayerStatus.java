package com.maha_fish.finaljudgment;

import com.maha_fish.finaljudgment.common.Constants;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;

/**
 * 四层状态查询（Unit 0b 冻结契约）+ tooltip 单行（方案 B）。
 */
public final class LayerStatus {

    private LayerStatus() {
    }

    public static boolean nativeReady() {
        return NativeBridge.isLoaded();
    }

    public static boolean agentReady() {
        return AgentBridge.isReady();
    }

    public static boolean coremodReady() {
        return "true".equals(System.getProperty(Constants.SYS_COREMOD_LOADED));
    }

    public static boolean bootstrapReady() {
        try {
            Class.forName("fj.bootstrap.Constants", false, null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Unit 0b 冻结格式（方案 B 单行）：§7Native: §a✓ §7| §7Agent: §a✓ … */
    public static String tooltipLine() {
        return layer(Constants.LAYER_NATIVE, nativeReady())
                + Constants.TOOLTIP_SEPARATOR
                + layer(Constants.LAYER_AGENT, agentReady())
                + Constants.TOOLTIP_SEPARATOR
                + layer(Constants.LAYER_COREMOD, coremodReady())
                + Constants.TOOLTIP_SEPARATOR
                + layer(Constants.LAYER_BOOTSTRAP, bootstrapReady());
    }

    private static String layer(String name, boolean ready) {
        return String.format(Constants.TOOLTIP_LAYER_TEMPLATE, name,
                ready ? Constants.TOOLTIP_READY : Constants.TOOLTIP_NOT_READY);
    }

    public static void logSnapshot() {
        FinalJudgment.LOGGER.info("[FJ] 层就绪: Native={} Agent={} Coremod={} Bootstrap={}",
                nativeReady(), agentReady(), coremodReady(), bootstrapReady());
        // Unit 7a 自检（不需要任何游戏内操作即可取证）
        FinalJudgment.LOGGER.info("[FJ] Unsafe 就绪: available={} 路径={}",
                com.maha_fish.finaljudgment.core.UnsafeAccess.available(),
                com.maha_fish.finaljudgment.core.UnsafeAccess.path());
        FinalJudgment.LOGGER.info("[FJ] FieldIsolation: 非玩家实体 isIsolated={}（规范 18 要求 false）",
                com.maha_fish.finaljudgment.core.FieldIsolation.isIsolated(new Object()));
        com.maha_fish.finaljudgment.core.ShadowHealth.selfTest();
        // 7b 探针：agent 侧 Patch 是否登记 / 是否命中（不走日志通道，直接读系统属性）
        FinalJudgment.LOGGER.info("[FJ] Agent Patch 诊断: registered={} hits={} last={}",
                System.getProperty("fj.agent.patch.registered", "?"),
                System.getProperty("fj.agent.patch.hits", "0"),
                System.getProperty("fj.agent.patch.last", "-"));
        // 防御接线诊断：armed 表示"当前有玩家背包里带锚点武器 → 防御链处于激活态"
        FinalJudgment.LOGGER.info("[FJ] 防御诊断: armed={} 保护守卫=coremod+Mixin 事件拦截=Attack/Hurt/Damage/Death",
                com.maha_fish.finaljudgment.attack.DefenseTicker.armed());
        // 7c 运行时诊断
        FinalJudgment.LOGGER.info("[FJ] 运行时诊断: heartbeat={} isolation={} checks={} entityListRebuilds={} audit={}",
                com.maha_fish.finaljudgment.core.HeartbeatThread.beats(),
                com.maha_fish.finaljudgment.core.IsolationManager.size(),
                com.maha_fish.finaljudgment.core.IsolationManager.checks(),
                com.maha_fish.finaljudgment.core.EntityListGuard.rebuilds(),
                com.maha_fish.finaljudgment.core.ReflectionAudit.enabled() ? "on" : "off");
        // 7b 一次性字段探测（用于回填 SRGNames 的 MCP/SRG 双候选）
        com.maha_fish.finaljudgment.core.StorageFieldProbe.logFields();
        // 诊断：区分「服务加载」与「transformer 真正被调用」
        FinalJudgment.LOGGER.info("[FJ] Coremod 诊断: registered={} transformHits={} last={}",
                System.getProperty("fj.coremod.registered", "?"),
                System.getProperty("fj.coremod.transform.hits", "0"),
                System.getProperty("fj.coremod.transform.last", "-"));
    }
}
