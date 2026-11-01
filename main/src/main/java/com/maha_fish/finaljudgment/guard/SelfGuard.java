package com.maha_fish.finaljudgment.guard;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自保护（对应示例项目的 {@code SelfGuardTransformer}）。
 *
 * <p>目标：防止其他 mod 通过反射/字节码手段替换我们自己的防护类
 * （例如把 {@code DefenseHandler} 的方法清空、把 {@code AntiKillBypass} 的字段翻回去）。</p>
 *
 * <p>做法：</p>
 * <ol>
 *   <li>启动时对关键类做 <b>SHA-256 基线快照</b>（从 ClassLoader 资源流读取字节）</li>
 *   <li>周期性重算并与基线比对；发现不一致立即 <b>WARN 日志 + 交给 {@link ClassRestorer} 还原</b></li>
 *   <li>全程不阻断游戏，只告警/修复，避免误伤导致整包崩溃</li>
 * </ol>
 */
public final class SelfGuard {

    /** 需要保护的关键类（内部名）。 */
    private static final String[] CRITICAL = {
            "com.maha_fish.finaljudgment.defense.DefenseHandler",
            "com.maha_fish.finaljudgment.defense.AntiDisarm",
            "com.maha_fish.finaljudgment.defense.EventBusGuard",
            "com.maha_fish.finaljudgment.attack.EntityRemover",
            "com.maha_fish.finaljudgment.attack.AntiKillBypass",
            "com.maha_fish.finaljudgment.attack.SpawnVeto",
            "com.maha_fish.finaljudgment.attack.KillChain",
            "com.maha_fish.finaljudgment.core.T0Anchor",
            "com.maha_fish.finaljudgment.coremod.KillFlag"
    };

    private static final Map<String, String> BASELINE = new LinkedHashMap<>();
    /** 方法级基线：类名 → (方法签名 → 方法体哈希)。 */
    private static final Map<String, Map<String, String>> METHOD_BASELINE = new LinkedHashMap<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong VIOLATIONS = new AtomicLong();

    private SelfGuard() {
    }

    /** 由防御 tick 调用，首次触发时建立基线并启动守护线程。 */
    public static void touch() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            for (String name : CRITICAL) {
                String hash = hashOf(name);
                if (hash != null) {
                    BASELINE.put(name, hash);
                }
                Map<String, String> methods = methodHashesOf(name);
                if (!methods.isEmpty()) {
                    METHOD_BASELINE.put(name, methods);
                }
            }
            FinalJudgment.LOGGER.info("[FJ] 自保护已启用: {} 个关键类建立基线", BASELINE.size());
            Thread thread = new Thread(SelfGuard::loop, "FJ-SelfGuard");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] 自保护启动失败: {}", t.toString());
        }
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(60_000L);
                verifyAll();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] 自保护校验异常: {}", t.toString());
            }
        }
    }

    /** 立即校验一轮。 */
    public static void verifyAll() {
        CHECKS.incrementAndGet();
        for (Map.Entry<String, String> entry : BASELINE.entrySet()) {
            String current = hashOf(entry.getKey());
            if (current == null) {
                continue;
            }
            if (!current.equals(entry.getValue())) {
                VIOLATIONS.incrementAndGet();
                FinalJudgment.LOGGER.warn("[FJ] 检测到关键类被改写: {}（尝试还原）", entry.getKey());
                // 方法级比对：指出具体哪个方法被改
                try {
                    Map<String, String> now = methodHashesOf(entry.getKey());
                    Map<String, String> base = METHOD_BASELINE.get(entry.getKey());
                    if (base != null && !now.isEmpty()) {
                        for (Map.Entry<String, String> m : base.entrySet()) {
                            String cur = now.get(m.getKey());
                            if (cur != null && !cur.equals(m.getValue())) {
                                FinalJudgment.LOGGER.warn("[FJ] 方法被改写: {}#{}", entry.getKey(), m.getKey());
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
                try {
                    ClassRestorer.restore(entry.getKey().replace('.', '/'));
                } catch (Throwable t) {
                    FinalJudgment.LOGGER.debug("[FJ] 还原失败: {}", t.toString());
                }
            }
        }
    }

    /** 读取类字节并算方法体哈希（方法级指纹，扩展 5/6-2）。 */
    private static Map<String, String> methodHashesOf(String className) {
        try {
            String resource = className.replace('.', '/') + ".class";
            try (InputStream in = SelfGuard.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    return java.util.Collections.emptyMap();
                }
                byte[] bytes = in.readAllBytes();
                return com.maha_fish.finaljudgment.guard.MethodFingerprint.methodHashes(bytes);
            }
        } catch (Throwable t) {
            return java.util.Collections.emptyMap();
        }
    }

    private static String hashOf(String className) {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = SelfGuard.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static int baselines() {
        return BASELINE.size();
    }

    public static long checks() {
        return CHECKS.get();
    }

    public static long violations() {
        return VIOLATIONS.get();
    }
}