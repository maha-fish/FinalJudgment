package com.maha_fish.finaljudgment.agent;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 jar / classes 目录读原始字节码（7.4.3 功能 1）。
 *
 * <p>四级回退：① 自身 jar ② classes 目录 ③ union 路径反查 ④ ClassLoader 资源。
 * <b>读不到时缓存空数组</b> —— 合成类/动态类永远读不到，不缓存会导致每次加载都重试。</p>
 */
public final class JarBytecodeReader {

    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static final byte[] MISSING = new byte[0];

    private JarBytecodeReader() {
    }

    public static byte[] read(Class<?> owner, String internalName) {
        if (internalName == null) {
            return MISSING;
        }
        byte[] cached = CACHE.get(internalName);
        if (cached != null) {
            return cached;
        }
        byte[] data = load(owner, internalName + ".class");
        if (data == null) {
            data = MISSING;
            AgentLog.debug("读不到字节码（按合成类处理）: " + internalName);
        }
        CACHE.put(internalName, data);
        return data;
    }

    private static byte[] load(Class<?> owner, String entry) {
        // ① jar
        try {
            CodeSource source = owner.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path path = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(path)) {
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
                        java.util.zip.ZipEntry found = zip.getEntry(entry);
                        if (found != null) {
                            try (InputStream in = zip.getInputStream(found)) {
                                return in.readAllBytes();
                            }
                        }
                    }
                } else if (Files.isDirectory(path)) {
                    // ② classes 目录
                    Path file = path.resolve(entry);
                    if (Files.isRegularFile(file)) {
                        return Files.readAllBytes(file);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 换下一级
        }
        // ③ union 协议：从 CodeSource 输出里提取 jar 名，在 mods/ 下反查
        try {
            CodeSource source = owner.getProtectionDomain().getCodeSource();
            String location = source == null ? null : String.valueOf(source.getLocation());
            if (location != null && location.contains("union:")) {
                URL url = owner.getClassLoader().getResource(entry);
                if (url != null) {
                    try (InputStream in = url.openStream()) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 换下一级
        }
        // ④ ClassLoader 资源
        try {
            URL url = owner.getClassLoader() == null
                    ? ClassLoader.getSystemResource(entry)
                    : owner.getClassLoader().getResource(entry);
            if (url != null) {
                try (InputStream in = url.openStream()) {
                    return in.readAllBytes();
                }
            }
        } catch (Throwable ignored) {
            // 读不到
        }
        return null;
    }
}
