package com.maha_fish.finaljudgment.nativebridge;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.TempResource;

import java.nio.file.Path;

/**
 * Native 桥接（7.3 功能 1、2）。
 *
 * <p>方法名即 JNI 符号名的一部分（包名里的下划线转义成 {@code _1}）：
 * {@code Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_<method>}。
 * <b>改名 = UnsatisfiedLinkError</b>。</p>
 */
public final class NativeBridge {

    /** 与 C 侧 FJ_PING_MAGIC 一致。 */
    public static final int PING_MAGIC = 0x464A0001;

    private static volatile boolean loaded;

    private NativeBridge() {
    }

    // ===== JNI 声明（与 native 模块的导出符号一一对应）=====
    public static native int nativePing();

    public static native void nativeInitLogger(Class<?> holder);

    public static native int nativeCapabilities();

    public static native void nativeSetSelfRetransform(boolean active);

    public static native boolean nativeInitGuard(Class<?> guard);

    public static native boolean nativeInjectBootstrap(String jarPath);

    public static native byte[] nativeCaptureRuntimeBytes(String internalName);

    public static native boolean nativeRedefineClass(String internalName, byte[] newBytes);

    public static native String[] nativeEnumerateLoadedClasses();

    public static native byte[] nativeGetConstantPool(String internalName);

    public static native int nativeRetransformClasses(Class<?>[] targets);

    /** C 侧日志回调入口，签名严格 {@code (Ljava/lang/String;)V}，不得改动。 */
    public static void nativeLog(String message) {
        FinalJudgment.LOGGER.info("[FJ-Native] {}", message);
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 加载流程（7.3 功能 1）：平台检测 → 提取 dll → System.load → nativeInitLogger → nativePing。
     * 任何一步失败都置 loaded=false 并打 WARN，<b>不抛异常</b>（规范 40）。
     */
    public static boolean load() {
        if (loaded) {
            return true;
        }
        if (!TempResource.isSupportedPlatform()) {
            return false;
        }
        try {
            Path dll = TempResource.extract(TempResource.nativeResourcePath(), ".dll");
            System.load(dll.toAbsolutePath().toString());
            FinalJudgment.LOGGER.info("[FJ-Native] dll 已加载: {}", dll);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ-Native] 加载失败（Native 层降级）: {}", t.toString());
            loaded = false;
            return false;
        }
        try {
            // 挂载时机：JNI_OnLoad 之后、探针之前（6.2 功能 9）
            nativeInitLogger(NativeBridge.class);
            int ping = nativePing();
            if (ping != PING_MAGIC) {
                FinalJudgment.LOGGER.warn("[FJ-Native] 探针魔数不符: 0x{}（期望 0x{}）",
                        Integer.toHexString(ping), Integer.toHexString(PING_MAGIC));
                loaded = false;
                return false;
            }
            loaded = true;
            FinalJudgment.LOGGER.info("[FJ-Native] 探针通过，Native 层就绪（能力位=0x{}）",
                    Integer.toHexString(nativeCapabilities()));
            return true;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ-Native] 探针/日志桥接失败（Native 层降级）: {}", t.toString());
            loaded = false;
            return false;
        }
    }
}
