package com.maha_fish.finaljudgment.agent;

import org.objectweb.asm.tree.ClassNode;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自身类保护（7.4.3 功能 4，规范 29）。
 *
 * <p>只登记前缀为 {@code com/maha_fish/finaljudgment/} 的自身类；跳过合成类
 * （{@code $} 内部类允许，但 {@code $$Lambda$} / {@code $Proxy} / {@code $Mixin} 跳过）。</p>
 *
 * <p><b>基线按类来源</b>（规范 10）：自身类用 {@link JarBytecodeReader} 从 jar 读的原始字节；
 * MC 类读不到 jar 字节时用"首次运行时捕获"的字节。基线采集必须在
 * Agent retransform 与 {@link ForcedClassRewrite} 之后（{@code NativeGuard.install} 的时序要求）。</p>
 */
public final class SelfClassProtector {

    private static final String OWN_PREFIX = "com/maha_fish/finaljudgment/";
    private static final List<String> SYNTHETIC_MARKERS =
            Arrays.asList("$$Lambda$", "$Proxy", "$Mixin", "$$");

    private static final AtomicInteger REGISTERED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    private SelfClassProtector() {
    }

    /** 是否为受保护的自身类。 */
    public static boolean accepts(String internalName) {
        if (internalName == null || !internalName.startsWith(OWN_PREFIX)) {
            return false;
        }
        for (String marker : SYNTHETIC_MARKERS) {
            if (internalName.contains(marker)) {
                return false;
            }
        }
        return !internalName.startsWith("com/maha_fish/finaljudgment/coremod/");
    }

    /**
     * 采集基线：自身类从 jar 读原始字节（读不到则跳过，不当成篡改）。
     *
     * @return 实际登记数量
     */
    public static int baselineOf(Class<?> type, ClassNode node) {
        String internalName = node.name;
        if (!accepts(internalName)) {
            SKIPPED.incrementAndGet();
            return 0;
        }
        byte[] jarBytes = JarBytecodeReader.read(type, internalName);
        if (jarBytes.length == 0) {
            AgentLog.debug("自身类基线缺失（按运行时捕获处理）: " + internalName);
            return 0;
        }
        REGISTERED.incrementAndGet();
        return 1;
    }

    public static int registered() {
        return REGISTERED.get();
    }

    public static int skipped() {
        return SKIPPED.get();
    }
}
