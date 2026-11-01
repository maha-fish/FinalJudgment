package com.maha_fish.finaljudgment;

import com.maha_fish.finaljudgment.common.Constants;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把主 mod jar 内的资源（agent jar / native 库 / bootstrap jar）提取到临时文件（7.2 功能 2、7.3 功能 1）。
 */
public final class TempResource {

    private TempResource() {
    }

    public static Path extract(String resourcePath, String suffix) throws Exception {
        try (InputStream in = TempResource.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("资源不存在: " + resourcePath);
            }
            Path file = Files.createTempFile("finaljudgment-", suffix);
            file.toFile().deleteOnExit();
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        }
    }

    /** 仅 Windows x86_64（ENV_SPEC 一）。 */
    public static boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean windows = os.contains("windows");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!windows || !x64) {
            FinalJudgment.LOGGER.warn("[FJ-Native] 不支持的平台: os={} arch={}（仅支持 Windows x86_64）", os, arch);
            return false;
        }
        return true;
    }

    public static String nativeResourcePath() {
        return Constants.RES_NATIVE_ROOT + Constants.NATIVE_PLATFORM_DIR + "/" + Constants.NATIVE_LIB_NAME;
    }
}
