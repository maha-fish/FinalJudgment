package com.maha_fish.finaljudgment.agent;

import com.sun.tools.attach.VirtualMachine;

import java.io.File;

/**
 * MANIFEST 的 Main-Class：在【子 JVM】里运行，附加到父进程并加载本 agent jar。
 *
 * <p>为什么必须开子 JVM：JDK 9+ 禁止自附加（ALLOW_ATTACH_SELF 是编译期常量），
 * 所以不可能在主 mod 进程里对自身 PID 调 {@code VirtualMachine.attach}（规范 22 / 4.2 功能 2）。</p>
 *
 * <p>用法：{@code java -jar finaljudgment-agent.jar <pid> <agentJar>}；退出码见常量。</p>
 */
public final class AttachLauncher {

    /** 附加成功 */
    public static final int EXIT_OK = 0;
    /** 参数错误 / agent jar 不存在 */
    public static final int EXIT_USAGE = 1;
    /** jdk.attach 不可用（jlink 裁剪过的 JRE）→ 主 mod 应提示回退 -javaagent */
    public static final int EXIT_NO_ATTACH_API = 2;
    /** VirtualMachine.attach 失败 */
    public static final int EXIT_ATTACH_FAILED = 3;
    /** loadAgent 失败 */
    public static final int EXIT_LOAD_FAILED = 4;

    private AttachLauncher() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args == null || args.length < 2) {
            System.err.println("[FJ-Agent] 用法: java -jar finaljudgment-agent.jar <pid> <agentJar>");
            return EXIT_USAGE;
        }
        String pid = args[0].trim();
        String agentJar = args[1].trim();
        // 可选第三个参数：agentArgs（逗号分隔的目标类内部名）
        String agentOptions = (args.length >= 3 && !args[2].trim().isEmpty()) ? args[2].trim() : null;

        // 1. 先检测 jdk.attach 是否可加载（规范 22）
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (Throwable t) {
            System.err.println("[FJ-Agent] jdk.attach 不可用: " + t);
            System.err.println("[FJ-Agent] 请使用完整 JDK 17（默认自带 jdk.attach），或用 -javaagent 回退");
            return EXIT_NO_ATTACH_API;
        }

        if (!new File(agentJar).isFile() || pid.isEmpty()) {
            System.err.println("[FJ-Agent] 参数无效: pid=" + pid + " agentJar=" + agentJar);
            return EXIT_USAGE;
        }

        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            System.out.println("[FJ-Agent] attach 成功: pid=" + pid);
            vm.loadAgent(agentJar, agentOptions);
            System.out.println("[FJ-Agent] loadAgent 成功: " + agentJar);
            return EXIT_OK;
        } catch (Throwable t) {
            if (vm == null) {
                System.err.println("[FJ-Agent] attach 失败: pid=" + pid + " (" + t + ")");
                return EXIT_ATTACH_FAILED;
            }
            System.err.println("[FJ-Agent] loadAgent 失败: " + agentJar + " (" + t + ")");
            return EXIT_LOAD_FAILED;
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                    System.out.println("[FJ-Agent] detach 完成");
                } catch (Throwable t) {
                    System.err.println("[FJ-Agent] detach 失败: " + t);
                }
            }
        }
    }
}
