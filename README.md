# Vulkanite

Vulkanite 为 Minecraft 26.3 Fabric 光影包提供可选的 Vulkan 光线追踪阶段。Minecraft、Sodium 与 Iris 仍负责 OpenGL 窗口、游戏界面和最终画面；不声明 Vulkanite 接口的光影包继续使用 Iris 的 OpenGL 渲染路径。

## Target

| Target | 加载器 | Java |
| --- | --- | --- |
| `26.3-fabric` | Fabric Loader 0.19.5 | 25 |

## 快速开始

安装与该 Target 匹配的 Vulkanite、Sodium、Iris 和兼容光影包，步骤与版本要求见[安装指南](documents/usage/installation-basics.md)和[依赖说明](documents/reference/dependency-runtime.md)。[Vulkanite Foundation](documents/design/architecture-foundation_pack.md) 是独立分发的参考光影包，使用前需单独取得它的 GPL-3.0-only ZIP。

## 文档

- [安装指南](documents/usage/installation-basics.md)
- [依赖说明](documents/reference/dependency-runtime.md)
- [光影包接口](documents/reference/format-shader_pack.md)
- [Vulkanite 架构](documents/design/architecture-vulkanite.md)
- [开发环境](documents/development/setup-environment.md)
- [日常工作流程](documents/development/workflow-daily.md)
