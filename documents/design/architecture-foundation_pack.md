# Foundation 光影包架构

`foundation_pack` 是生成 Vulkanite Foundation 独立发行 ZIP 的 `tool` 组件。它提供用于演示 Vulkanite 光影包接口的参考内容；压缩包根目录包含 `shaders/`、`README.md` 与 `NOTICE.md`，不并入 Vulkanite 模组 JAR。

## 内容与行为

Foundation 是路径追踪参考光影包，提供样本数与反弹次数控制，并使用 Iris 管理曝光和显示输出。光线追踪阶段按 [光影包接口](../reference/format-shader_pack.md) 声明资源与执行位置。

光影包内还包含 OpenDRT 实现及 `ImportOpenDrt.py` 导入工具。上游代码、许可与归属说明由 Foundation ZIP 自带的 `NOTICE.md` 记录；本仓库根目录的许可目录不代表 Foundation 的许可。

## 发行边界

Foundation 采用 GPL-3.0-only，并作为单独的 ZIP 分发。使用者需要把该 ZIP 放入游戏的 `shaderpacks` 文件夹，再从 Iris 的光影包选择界面启用。它不是 Vulkanite 的运行依赖，Vulkanite JAR 中不包含 Foundation 的着色器、说明或许可文件。

Foundation 的构建工具属于 `tool` 组件，只负责生成和检查独立压缩包，不参与 Target 的 `product` 合并。输出根目录结构必须保持为 `shaders/`、`README.md` 与 `NOTICE.md`；不得把源码树外的生成文件或项目开发脚本放入该发行包。
