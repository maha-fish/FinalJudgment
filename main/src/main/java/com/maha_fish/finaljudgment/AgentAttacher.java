package com.maha_fish.finaljudgment;

import com.maha_fish.finaljudgment.common.Constants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 动态附加（7.2 功能 2）：提取 agent jar → 起子 JVM → 转发输出 → 等待 → 轮询 InstHolder。
 *
 * <p>为什么开子 JVM：JDK 9+ 禁止自附加（ALLOW_ATTACH_SELF 是编译期常量），
 * 所以在自身进程里对自身 PID 调 {@code VirtualMachine.attach} 一定失败（规范 22）。</p>
 */
public final class AgentAttacher {

    private static final long ATTACH_TIMEOUT_SECONDS = 10L;
    private static final long POLL_INTERVAL_MS = 100L;
    private static final long POLL_TIMEOUT_MS = 5000L;

    private AgentAttacher() {
    }

    public static boolean attachFromResource() {
        if (AgentBridge.isReady()) {
            FinalJudgment.LOGGER.info("[FJ-Agent] 已就绪，跳过附加");
            return true;
        }
        if (!isAttachApiAvailable()) {
            FinalJudgment.LOGGER.warn("[FJ-Agent] jdk.attach 不可用：请使用完整 JDK 17，或用 -javaagent 回退");
            return false;
        }

        Path agentJar;
        try {
            agentJar = TempResource.extract(Constants.RES_AGENT_JAR, ".jar");
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ-Agent] 提取 agent jar 失败: {}", t.toString());
            return false;
        }

        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        long pid = ProcessHandle.current().pid();

        try {
            List<String> command = new ArrayList<>();
            command.add(javaExe);
            // 子 JVM 的输出按 UTF-8 编码，避免中文在日志里变乱码
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dsun.stdout.encoding=UTF-8");
            command.add("-Dsun.stderr.encoding=UTF-8");
            command.add("-jar");
            command.add(agentJar.toAbsolutePath().toString());
            command.add(Long.toString(pid));
            command.add(agentJar.toAbsolutePath().toString());

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    FinalJudgment.LOGGER.info("[FJ-Agent] {}", line);
                }
            }
            if (!process.waitFor(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                FinalJudgment.LOGGER.warn("[FJ-Agent] 附加超时（{} 秒）", ATTACH_TIMEOUT_SECONDS);
                return false;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                FinalJudgment.LOGGER.warn("[FJ-Agent] 子 JVM 退出码={}（{}）", exit, exitHint(exit));
                return false;
            }
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ-Agent] 附加失败: {}", t.toString());
            return false;
        }

        // 轮询等待 Instrumentation 到位（agentmain 是异步的）
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (AgentBridge.isReady()) {
                AgentBridge.installLogSink();
                FinalJudgment.LOGGER.info("[FJ-Agent] 附加成功，Instrumentation 已就绪");
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        FinalJudgment.LOGGER.warn("[FJ-Agent] 轮询超时：Instrumentation 未到位（{} ms）", POLL_TIMEOUT_MS);
        return false;
    }

    private static boolean isAttachApiAvailable() {
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String exitHint(int exit) {
        switch (exit) {
            case 1:
                return "参数错误";
            case 2:
                return "jdk.attach 不可用，需完整 JDK 或 -javaagent";
            case 3:
                return "attach 失败";
            case 4:
                return "loadAgent 失败";
            default:
                return "未知";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
