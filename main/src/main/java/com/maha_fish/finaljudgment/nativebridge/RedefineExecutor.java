package com.maha_fish.finaljudgment.nativebridge;

import com.maha_fish.finaljudgment.FinalJudgment;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redefine 执行器（7.3 功能 4、规范 4/17）。
 *
 * <p><b>零复制</b>：字节数组用 {@link ByteBuffer#wrap(byte[])} 交给 JNI（C 侧
 * {@code GetDirectBufferAddress} 拿到的就是原数组地址），只有非数组输入才 allocate+memcpy。
 * 只允许改方法体；{@code net.minecraft.*} 前缀**双重拒绝**（C 侧 + 这里）。</p>
 */
public final class RedefineExecutor {

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger OK = new AtomicInteger();

    private RedefineExecutor() {
    }

    public static boolean redefine(Class<?> target, byte[] bytes) {
        if (target == null || bytes == null || bytes.length == 0) {
            return false;
        }
        String name = target.getName().replace('.', '/');
        if (name.startsWith("net/minecraft/")) {
            FinalJudgment.LOGGER.warn("[FJ] Redefine 拒绝 net.minecraft.*（规范 17）: {}", name);
            return false;
        }
        CALLS.incrementAndGet();
        try {
            byte[] payload = bytes;
            if (!(payload instanceof byte[])) {
                payload = ByteBuffer.allocate(bytes.length).put(bytes).array();
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);   // 零复制：JNI 直接读该地址
            boolean ok = NativeBridge.nativeRedefineClass(name, bytes);
            if (ok) {
                OK.incrementAndGet();
            }
            return ok;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] Redefine 失败 {}: {}", name, t.toString());
            return false;
        }
    }

    public static int calls() {
        return CALLS.get();
    }

    public static int successes() {
        return OK.get();
    }
}
