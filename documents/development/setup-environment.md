# 开发环境

本仓库只有 `26.3-fabric` 一个 Target，需要 JDK 25 与网络。Gradle Wrapper 固定为 9.8.1；其余 Java 工具链与构建依赖由 Gradle 按项目配置取得。

## 需要的软件

| 软件 | 说明 |
| --- | --- |
| JDK 25 | 运行 Gradle 守护进程并编译 `26.3-fabric`。[gradle-daemon-jvm.properties](../../gradle/gradle-daemon-jvm.properties) 让 Gradle 在本机查找 JDK 25，找不到时自动下载 |
| Gradle 9.8.1 | 由仓库的 Gradle Wrapper 固定与启动，不需要单独安装 |
| Git | 合规检查通过 Git 确定哪些文件属于仓库 |
| 网络 | 首次构建需要下载 Minecraft、Fabric Loader、模组依赖与格式化工具；Markdown 检查工具 rumdl 的版本固定在 [libs.versions.toml](../../gradle/libs.versions.toml) |

## 首次构建

```powershell
.\gradlew.bat build
```

首次构建会下载并处理每个已登记 Target 的 Minecraft 与加载器，耗时较长；之后的构建使用缓存。Unix 使用 `./gradlew`。

## IDE

使用 IntelliJ IDEA 时打开仓库根目录，并把 Gradle JVM 设为 JDK 25。Fabric Loom 会为每个日常实例生成运行配置。

`automation` 中的 PowerShell 客户端驱动需要 Windows；普通编译、合规检查与文档检查不依赖它。项目依赖的运行库与开发库见[依赖说明](../reference/dependency-runtime.md)。

## 本机配置

在仓库根目录创建 `local.toml`，它被 Git 忽略，用于个人偏好和本地输入路径。示例只设置客户端内存：

```toml
[client]
memory-max = "6G"
```

实例设置与 [instances.toml](../../instances.toml) 使用相同的表，写在同名的表中才能替换共享预设，规则见 [configuration-repository](../reference/configuration-repository.md)。Vulkanite 是客户端模组，开发产品行为不需要接受 Minecraft 服务端 EULA。

项目在 `inputs.toml` 中声明本地输入时，按其中的 `description` 与 `source` 取得文件，把路径写进 `[inputs]`，再执行 `.\gradlew.bat verifyInputs` 确认路径与哈希。
