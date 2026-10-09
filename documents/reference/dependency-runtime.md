# 运行与构建依赖

Vulkanite 面向 Minecraft 26.3 Fabric 客户端，运行时需要 Fabric Loader、Sodium 与 Iris。Target 事实由 `versions/26.3-fabric/target.properties` 管理；下表同步其玩家可见的兼容要求。

## 玩家所需依赖

| 依赖 | 版本要求 | 用途 |
| --- | --- | --- |
| Minecraft Java 版 | 26.3 | 唯一支持的 Minecraft 版本 |
| Fabric Loader | 0.19.5 或更高版本 | 加载客户端模组 |
| Sodium | 0.9.2 | 提供 Vulkanite 捕获的地形数据 |
| Iris | 1.11.6 | 管理光影包与 COMPOSITE 执行边界 |
| Java | 25 或更高版本 | 运行 Minecraft 26.3 与模组 |

客户端只需安装 Vulkanite。服务器不需要安装它；模组不会在服务端运行。

## 构建依赖

这些版本由 Target 事实或共用版本目录管理，不应在构建脚本、源码或元数据中另行固定。

| 依赖 | 版本 | 范围 |
| --- | --- | --- |
| Fabric API | `0.160.7+26.3` | 开发运行类路径；模组元数据不要求玩家另行安装 |
| LWJGL | `3.4.3` | 运行时库版本基线；Minecraft 提供的 GLFW 接口作为编译期依赖 |
| LWJGL meshoptimizer | `3.4.3` | 随模组嵌入，并包含 Windows 与 Linux 的原生库 |
| JOML | `1.10.9` | Java 矩阵与向量运算 |
| Gradle | `9.8.1` | Wrapper 固定的构建工具版本 |
| Java 工具链 | `25` | 编译与 Gradle 守护进程使用的工具链 |

依赖版本按用途存放：Target 专属版本写入 `versions/26.3-fabric/target.properties`，跨 Target 共用的构建工具与库写入 `gradle/libs.versions.toml`。新增或升级依赖时，按 [依赖升级规程](../development/procedure-update_dependencies.md) 更新元数据、许可与本说明。

## Foundation

Vulkanite Foundation 是独立发行的 GPL-3.0-only 光影包 ZIP，不是运行模组的依赖，也不会合并进 Vulkanite JAR。安装时把该 ZIP 单独放入游戏的 `shaderpacks` 文件夹。发行包本身带有 `README.md` 与 `NOTICE.md`。
