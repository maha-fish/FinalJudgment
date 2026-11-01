package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.nativebridge.NativeBridge;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反分析（对应示例项目 {@code NativeAntiAnalysis}）。
 *
 * <p>检测当前 JVM 是否处于"被分析/被注入"状态，并把结论记进状态位，
 * 供 {@code LayerStatus} / {@code WiringAudit} 输出。</p>
 *
 * <p>检测项：</p>
 * <ol>
 *   <li>启动参数里是否有额外的 {@code -javaagent}（除了我们自己的）</li>
 *   <li>是否存在调试类 agent（jdwp / debug / profiler 关键字）</li>
 *   <li>native 层能力位是否退化（期望 {@code 0x1f}）</li>
 * </ol>
 *
 * <p>只报告，不主动对抗（避免误伤正常环境）。</p>
 */
public final class NativeAntiAnalysis {

    private static final AtomicBoolean SCANNED = new AtomicBoolean(false);
    private static final AtomicInteger SUSPICIOUS = new AtomicInteger();
    private static final StringBuilder REPORT = new StringBuilder();

    private NativeAntiAnalysis() {
    }

    /** 执行一次分析（幂等）。 */
    public static void scan() {
        if (!SCANNED.compareAndSet(false, true)) {
            return;
        }
        try {
            int agents = 0;
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String lower = arg.toLowerCase();
                if (lower.contains("-javaagent") || lower.contains("-agentlib")) {
                    agents++;
                    if (lower.contains("jdwp") || lower.contains("debug") || lower.contains("profiler")
                            || lower.contains("attach")) {
                        SUSPICIOUS.incrementAndGet();
                        REPORT.append("\n[FJ] 可疑 agent: ").append(arg);
                    }
                }
            }
            if (agents > 1) {
                SUSPICIOUS.incrementAndGet();
                REPORT.append("\n[FJ] 检测到 ").append(agents).append(" 个 agent（预期 1 个：本模组）");
            }
            try {
                int caps = NativeBridge.nativeCapabilities();
                if ((caps & 0x1f) != 0x1f) {
                    SUSPICIOUS.incrementAndGet();
                    REPORT.append("\n[FJ] native 能力位退化: 0x").append(Integer.toHexString(caps));
                }
            } catch (Throwable ignored) {
                // native 未加载不算可疑
            }
            FinalJudgment.LOGGER.info("[FJ] 反分析完成: 可疑项 {} 个{}", SUSPICIOUS.get(), REPORT);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 反分析异常: {}", t.toString());
        }
    }

    public static int suspicious() {
        return SUSPICIOUS.get();
    }
}