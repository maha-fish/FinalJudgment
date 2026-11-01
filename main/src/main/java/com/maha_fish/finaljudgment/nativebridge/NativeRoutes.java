package com.maha_fish.finaljudgment.nativebridge;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.common.Constants;
import com.maha_fish.finaljudgment.guard.NativeGuard;

/**
 * 三条 Native 路线（8.2 step 24）。
 *
 * <p>顺序固定：<b>Bootstrap 注入 → Analysis（枚举/常量池）→ Redefine（类改写）</b>。
 * 每条独立 try-catch，失败只 WARN（规范 40），前一条失败不影响后一条。</p>
 */
public final class NativeRoutes {

    private static volatile boolean done;

    private NativeRoutes() {
    }

    public static void run() {
        if (done || !NativeBridge.isLoaded()) {
            FinalJudgment.LOGGER.debug("[FJ] Native 路线跳过（nativeReady={}）", NativeBridge.isLoaded());
            return;
        }
        done = true;

        // ① Bootstrap：注入 bootstrap jar，让所有 ClassLoader 都能读到跨层常量
        try {
            java.nio.file.Path jar = com.maha_fish.finaljudgment.TempResource.extract(
                    Constants.RES_BOOTSTRAP_JAR, ".jar");
            boolean ok = NativeBridge.nativeInjectBootstrap(jar.toAbsolutePath().toString());
            FinalJudgment.LOGGER.info("[FJ] Native 路线① Bootstrap 注入: {}", ok);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] Native 路线① 失败: {}", t.toString());
        }

        // ② Analysis：枚举已加载类（只统计，避免每次全量打印）
        try {
            String[] classes = NativeBridge.nativeEnumerateLoadedClasses();
            FinalJudgment.LOGGER.info("[FJ] Native 路线② Analysis: 已加载类 {} 个", classes == null ? 0 : classes.length);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] Native 路线② 失败: {}", t.toString());
        }

        // ③ Redefine：先采集受保护类基线（ClassRestorer），再做就绪性检查
        try {
            for (String internalName : new String[]{
                    "net/minecraft/world/entity/Entity",
                    "net/minecraft/world/entity/LivingEntity",
                    "net/minecraft/server/level/ServerPlayer",
                    "net/minecraft/server/level/ServerLevel",
                    "net/minecraft/client/Minecraft"}) {
                com.maha_fish.finaljudgment.guard.ClassRestorer.capture(internalName);
            }
            FinalJudgment.LOGGER.info("[FJ] Native 路线③ Redefine: 就绪（NativeGuard 基线 {} 条，ClassRestorer 基线 {} 条）",
                    NativeGuard.baselines(), com.maha_fish.finaljudgment.guard.ClassRestorer.baselines());
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] Native 路线③ 失败: {}", t.toString());
        }
    }
}
