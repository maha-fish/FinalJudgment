终焉裁定 —— 项目环境与构建规范（Windows 最终版·修订二）

仅针对 Windows x86_64。
主开发/测试平台：Windows。
不支持：Linux、macOS、安卓。
本文档以《终焉裁定模组重置.md》为主文档，冲突时以重置指南为准。

---

一、目标平台

· Minecraft: 1.20.1
· Forge: 47.4.10
· Mappings: official / 1.20.1
· 主开发/测试平台: Windows x86_64
· 仅支持平台: Windows x86_64
· 不支持: Linux、macOS、安卓（PojavLauncher 无法编译加载 Native）

---

二、开发环境要求

· JDK: 17（必须）
· Gradle: 项目自带 wrapper（Forge 1.20.1 对应 Gradle 8.1.1）
· ForgeGradle: [6.0,6.2)
· Mixin 插件: 0.7.+
· C 编译器: gcc（MinGW-w64）
· C 编译器固定路径: C:/mingw64/bin/gcc.exe
· IDE 推荐: IntelliJ IDEA

---

三、项目模块结构

```
finaljudgment/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── common/     <- 契约：SRG 名映射、全局常量、接口
├── native/     <- C 代码
├── agent/      <- Agent jar（独立打包，自带 ASM）
├── coremod/    <- Coremod jar（独立打包）
├── bootstrap/  <- Bootstrap jar（独立打包）
└── main/       <- 主 mod
```

依赖关系：

· main 依赖 common、native、agent、bootstrap
· main 不依赖 coremod（编译期不引用，Coremod 独立放 mods/）
· common 无内部依赖，被所有 Java 模块依赖
· coremod、agent、native、bootstrap 各自独立
· 禁止反向依赖

settings.gradle：

```groovy
rootProject.name = 'finaljudgment'
include 'common', 'native', 'agent', 'coremod', 'bootstrap', 'main'
```

---

四、构建产物（5 类）

4.1 主 mod jar

· 路径: build/libs/finaljudgment-<version>.jar
· 内容: 主 mod Java 类 + 资源 + Mixin + 嵌入 agent jar + 嵌入 native 库 + 嵌入 bootstrap jar
· 安装位置: mods/

4.2 Coremod jar

· 路径: coremod/build/libs/finaljudgment-coremod-<version>-transformer.jar
· 要求:
  · jar 文件名必须以 -transformer 结尾（严格小写）
  · MANIFEST.MF 声明 FMLModType: MODLAUNCHER
    （修订：原 GAMELIBRARY 只把 jar 当游戏库——服务会被加载、但 ITransformer 永不参与类变换，
     实测 transformHits=0；改 MODLAUNCHER 后注入正常）
  · META-INF/services/cpw.mods.modlauncher.api.ITransformationService 内容为实现类全限定名
  · mods.toml 不含 modType 字段
· 不能: 通过 jarJar 嵌入主 mod
· 安装位置: mods/

4.3 Agent jar

· 路径: agent/build/libs/finaljudgment-agent-<version>.jar
· 要求:
  · MANIFEST.MF 同时声明 Main-Class / Premain-Class / Agent-Class
  · Can-Redefine-Classes: true / Can-Retransform-Classes: true
  · Agent 必须自带 ASM（使用 implementation 或 shadow 打包），不能只依赖 compileOnly
  · 不能直接放进 mods/
· 部署: 嵌入主 mod jar 的 META-INF/agent/，运行时由主 mod 提取

4.4 Native 库

· 路径: native/build/native/windows-x86_64/
· 文件名: finaljudgment_native.dll
· 部署: 嵌入主 mod jar 的 META-INF/native/windows-x86_64/

4.5 Bootstrap jar

· 路径: bootstrap/build/libs/finaljudgment-bootstrap-<version>.jar
· 要求:
  · 只含常量和基础类型
  · 不能 import 任何 net.minecraft.* / net.minecraftforge.* / com.maha_fish.finaljudgment.*
  · 可引用任意 JDK 类（java.* / javax.* / jdk.*），但建议只放常量和基础类型
· 部署: 嵌入主 mod jar 的 META-INF/bootstrap/finaljudgment-bootstrap.jar

---

五、用户安装说明

把以下两个文件放进 mods/：

· finaljudgment-<version>.jar
· finaljudgment-coremod-<version>-transformer.jar

Agent、Native、Bootstrap 由主 mod 自动提取，用户无需手动放置。

---

六、C 编译器配置（Windows）

编译器: gcc（MinGW-w64）

固定路径:

```
C:/mingw64/bin/gcc.exe
```

优先级:

1. -Pgcc.path=<path>
2. gradle.properties 中的 gcc.path
3. 环境变量 FJ_GCC
4. 默认值 C:/mingw64/bin/gcc.exe

建议在 gradle.properties 中写入：

```properties
gcc.path=C:/mingw64/bin/gcc.exe
```

编译命令：

```
gcc -shared -O2 -Wall -Wno-unused-parameter
    -o finaljudgment_native.dll
    -I<jdk_home>/include
    -I<jdk_home>/include/win32
    <所有 .c 文件>
    -lws2_32
```

必须头文件:

· jni.h <- <jdk_home>/include/
· jni_md.h <- <jdk_home>/include/win32
· jvmti.h <- <jdk_home>/include/

---

七、Java 依赖

```groovy
dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.4.10'
    annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'
}
```

ASM 依赖策略:

模块 依赖方式 原因
coremod compileOnly ModLauncher 提供 ASM
agent implementation / shadow Agent 可能独立运行，必须自带 ASM
main implementation 主 mod 需要独立 ASM
bootstrap 无 避免污染 Bootstrap
common compileOnly resolveField 需要编译期 ASM 类型，运行期由调用方提供

---

八、Gradle 全局配置

根 build.gradle：

```groovy
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)' apply false
    id 'org.spongepowered.mixin' version '0.7.+' apply false
}

subprojects {
    tasks.withType(JavaCompile).configureEach {
        options.encoding = 'UTF-8'
    }
}
```

原因: Windows 默认 GBK 编码，中文注释会报“不可映射字符”。

---

九、模块间构建依赖

主 mod 的 compileJava 必须依赖:

· :agent:jar
· :native:compileNative
· :bootstrap:jar

不要依赖 :coremod:jar。Coremod 是独立放 mods/ 的。

主 mod 的 processResources 必须依赖:

· :agent:jar
· :native:compileNative
· :bootstrap:jar

嵌入逻辑示例:

```groovy
processResources {
    dependsOn ':agent:jar', ':native:compileNative', ':bootstrap:jar'

    from(tasks.getByPath(':agent:jar').archiveFile) {
        into 'META-INF/agent'
        rename { 'finaljudgment-agent.jar' }
    }

    from("${rootProject.projectDir}/native/build/native") {
        into 'META-INF/native'
    }

    from(tasks.getByPath(':bootstrap:jar').archiveFile) {
        into 'META-INF/bootstrap'
        rename { 'finaljudgment-bootstrap.jar' }
    }
}
```

Native 编译任务示例:

```groovy
tasks.register('compileNative', Exec) {
    def gcc = project.findProperty('gcc.path')
        ?: System.getenv('FJ_GCC')
        ?: 'C:/mingw64/bin/gcc.exe'
    def jdkHome = System.getProperty('java.home')
    def outDir = file('build/native/windows-x86_64')
    def out = 'finaljudgment_native.dll'

    doFirst {
        outDir.mkdirs()
    }

    commandLine gcc, '-shared', '-O2', '-Wall', '-Wno-unused-parameter',
        '-o', new File(outDir, out).absolutePath,
        "-I${jdkHome}/include",
        "-I${jdkHome}/include/win32",
        fileTree('src/main/c').matching { include '**/*.c' }.files,
        '-lws2_32'
}
```

---

十、dev 环境 JVM 参数

标准路径不需要任何 --add-opens，也不需要 --add-modules jdk.attach。

· 规范 11：标准路径不加 JVM 参数，走 Unsafe 直写 + IMPL_LOOKUP + 标准 JDK，dev 和生产都不需要 --add-opens。
· 规范 12：禁止 setAccessible 访问 java.base，改用 Unsafe 直写；setAccessible 会引入 JVM 参数依赖。
· 规范 22：标准 JDK 17 默认包含 jdk.attach，不需要 --add-modules；只有 jlink 裁剪过的 JRE 才缺失，此时回退 -javaagent。

因此 build.gradle 的 runs 配置不应默认添加 --add-opens 或 --add-modules jdk.attach。
若为兼容旧版本或调试保留，必须注释说明，并默认不启用。

---

十一、SRG 名说明

· Dev 环境编译: 方法名/字段名是 Mojang 名（如 getHealth / removalReason）
· 生产环境运行时: 字节码里是 SRG 名（如 m_21223_ / f_146795_）
· 所有字节码匹配（ASM、反射字段名）必须双候选
· 字段名优先用描述符从 ClassNode 解析
· C 侧禁止硬编码 SRG 名，应由 Java 传入或生成 C 头文件

---

十二、Mixin 配置

主 mod 的 build.gradle 需要：

```groovy
mixin {
    add sourceSets.main, 'finaljudgment.refmap.json'
    config 'finaljudgment.mixins.json'
}
```

finaljudgment.mixins.json 中的 refmap 字段必须与输出名一致。

防缴械相关 Mixin 必须列入，例如：

· MixinInventory
· MixinContainerMenu
· MixinServerGamePacketListener
· MixinServerPlayerGameMode

---

十三、常用命令

```
gradlew.bat clean build              完整构建
gradlew.bat :native:compileNative    只编译 C
gradlew.bat :agent:jar               只打 agent jar
gradlew.bat :coremod:jar             只打 coremod jar
gradlew.bat :bootstrap:jar           只打 bootstrap jar
gradlew.bat runClient                启动 dev 客户端
gradlew.bat runServer                启动 dev 服务端
```

---

十四、日志位置

· dev 环境: <project>/run/logs/latest.log
· 生产环境: .minecraft/logs/latest.log
· 崩溃报告: .minecraft/crash-reports/

---

十五、构建后验证清单

☐ build/libs/finaljudgment-<version>.jar 存在
☐ coremod/build/libs/finaljudgment-coremod-<version>-transformer.jar 存在，且文件名以 -transformer 结尾
☐ agent/build/libs/finaljudgment-agent-<version>.jar 存在
☐ native/build/native/windows-x86_64/finaljudgment_native.dll 存在
☐ bootstrap/build/libs/finaljudgment-bootstrap-<version>.jar 存在
☐ 主 jar 内解压可见 META-INF/agent/finaljudgment-agent.jar
☐ 主 jar 内解压可见 META-INF/native/windows-x86_64/finaljudgment_native.dll
☐ 主 jar 内解压可见 META-INF/bootstrap/finaljudgment-bootstrap.jar
☐ Coremod jar 内解压可见 META-INF/services/cpw.mods.modlauncher.api.ITransformationService
☐ Coremod jar 的 MANIFEST.MF 有 FMLModType: GAMELIBRARY
☐ Coremod 的 mods.toml 不含 modType 字段
☐ Agent jar 的 MANIFEST.MF 有 Main-Class / Premain-Class / Agent-Class
☐ Agent jar 的 MANIFEST.MF 有 Can-Redefine-Classes: true / Can-Retransform-Classes: true
☐ ./gradlew build 无 VerifyError、无编码错误

---

十六、常见错误提醒

1. mods.toml 写 modType：Forge 1.20.1 没有该字段，Coremod 类型只由 MANIFEST.MF 的 FMLModType 决定。
2. Coremod 文件名不以 -transformer 结尾：Coremod 完全不加载。必须严格小写且是文件名结尾。
3. 模块依赖方向写反：common 不依赖任何模块；native、agent、coremod、bootstrap 各自独立；main 依赖 common、native、agent、bootstrap，不依赖 coremod。
4. Native 文件名：Windows 下是 finaljudgment_native.dll，无 lib 前缀。
5. JNI 头文件路径：Windows 用 include/win32。
6. 链接库：Windows 需要 -lws2_32。
7. ASM 依赖：common 用 compileOnly（resolveField 需要编译期 ASM 类型，运行期由调用方提供）；coremod 用 compileOnly；agent 必须自带 ASM（implementation 或 shadow）；main 用 implementation。
8. 缺少 settings.gradle：多模块项目必须声明所有子模块。
9. 缺少 ForgeGradle 版本：必须声明 [6.0,6.2)。
10. 缺少 Mixin refmap：主 mod 必须配置 mixin { add sourceSets.main, 'finaljudgment.refmap.json' }。
11. 嵌入逻辑：processResources 必须依赖 agent / native / bootstrap，并正确复制到 META-INF/agent、META-INF/native、META-INF/bootstrap。
12. 防缴械 Mixin 未列入 mixins.json：防缴械机制没有注入点，功能完全不生效。
13. C 侧硬编码 SRG 名：违反“SRG 名集中管理”，生产环境可能静默失效。
14. Agent 不能直接引用主 mod 的 NativeBridge：跨 ClassLoader 必须走反射或 Bootstrap 共享层。
15. Bootstrap 类不能引用 MC / Forge / mod 类：否则 NoClassDefFoundError。
16. 默认添加 --add-opens 或 --add-modules jdk.attach：标准路径不需要，会引入不必要的 JVM 参数依赖；应删除或仅作注释保留。
17. 使用 setAccessible 访问 java.base 私有成员：违反规范 30，改用 Unsafe 直写。

---

文档结束。