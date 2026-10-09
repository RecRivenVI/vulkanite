# 项目规范

本文件是本项目的项目规范，对整个仓库生效。它补充根目录的[仓库规范](../AGENTS.md)，不能放宽或改写其中的规则。

## 项目信息

| 项 | 值 |
| --- | --- |
| 产品显示名 | Vulkanite |
| 模组 ID | `vulkanite` |
| Java 包 | `me.cortex.vulkanite` |
| 入口类 | 无；`MixinNewWorldRenderingPipeline` 等 Mixin 在 Iris 提交兼容光影包时按需初始化 Vulkanite |
| 业务 | 为 Minecraft 26.3 Fabric 光影包提供由光影包控制的 Vulkan 光线追踪阶段 |
| 文档主语言 | 简体中文 |
| 首页语言 | 简体中文 |
| 参考 Target | `26.3-fabric` |

## Target

| Target | 加载器版本 | Java |
| --- | --- | --- |
| `26.3-fabric` | 0.19.5 | 25 |

## 补充规则

- **X-01** [判断] Vulkanite Foundation 以独立的 GPL-3.0-only ZIP 发布，其文件不进入模组 JAR。
- **X-02** [判断] 自动化只使用仓库内的临时实例；不启动、修改或将使用者的 Prism 实例用于自动化检查。
