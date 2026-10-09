# 安装 Vulkanite

适用于 Minecraft Java 版 26.3 Fabric。安装 Vulkanite、匹配版本的 Sodium 与 Iris，以及支持 Vulkanite 接口的光影包后，可以在 Iris 中启用光影包请求的 Vulkan 光线追踪阶段。

## 选择文件

每个模组文件只适用于表中版本。请使用项目发行页提供的 `26.3-fabric` 文件，并确认启动器选择的是 Minecraft 26.3 Fabric 配置。

| 内容 | 版本 | 放置位置 |
| --- | --- | --- |
| Vulkanite | `0.0.4-pre-alpha+26.3` | 游戏目录的 `mods` 文件夹 |
| Fabric Loader | `0.19.5` 或更高版本 | 由启动器安装 |
| Sodium | `0.9.2` | 游戏目录的 `mods` 文件夹 |
| Iris | `1.11.6` | 游戏目录的 `mods` 文件夹 |
| Java | 25 或更高版本 | 由启动器配置 |

Vulkanite 是客户端模组。服务器不需要安装它，你可以用已安装 Vulkanite 的客户端加入未安装本模组的服务器。完整的依赖范围与构建依赖见[依赖说明](../reference/dependency-runtime.md)。

## 安装步骤

1. 在启动器中安装 Minecraft 26.3 Fabric，并选择 Java 25 运行时。
2. 下载与表中版本相符的 Vulkanite、Sodium 和 Iris 文件。
3. 关闭游戏，把三个 JAR 文件放入该游戏配置的 `mods` 文件夹。
4. 启动 Minecraft 26.3 Fabric，确认主菜单正常显示。

## 启用光影包

1. 取得一个实现了 [Vulkanite 光影包接口](../reference/format-shader_pack.md)的光影包。
2. 把光影包 ZIP 放入游戏目录的 `shaderpacks` 文件夹；保留 ZIP 内部目录结构。
3. 在游戏的视频设置中打开 Iris 的光影包选择界面并启用该光影包。

没有 Vulkanite 声明与光线追踪阶段的普通 Iris 光影包仍按其 OpenGL 路径运行。Vulkanite Foundation 是独立的 GPL-3.0-only 光影包 ZIP，不包含在 Vulkanite 模组 JAR 中；该 ZIP 自带玩家说明与许可通知。

## 确认安装

回到主菜单后，打开游戏目录的 `logs/latest.log`，搜索 `Vulkanite`。若加载日志中没有缺少 Fabric Loader、Sodium 或 Iris 的报错，即表示模组已被加载。选择兼容光影包后，游戏日志会显示光影包合同或 Vulkan 初始化错误；遇到错误时，请先核对光影包接口版本和[依赖版本](../reference/dependency-runtime.md)。
