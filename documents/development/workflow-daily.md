# 日常工作流程

Vulkanite 当前只有 `26.3-fabric` 一个 Target。日常开发在仓库根目录进行；规则以[仓库规范](../../AGENTS.md)和[项目规范](../AGENTS.md)为准。

## 修改

产品代码按根规范放入 `versions/26.3-fabric/src/client/`；Fabric 元数据放在 `src/main/resources/`，只供游戏运行的验证代码放在 `src/probe/`。自动化与 Foundation ZIP 的构建工具分别属于 `automation` 和 `foundation_pack` 两个 `tool` 组件，职责见[自动化工具架构](../design/architecture-automation.md)和[Foundation 架构](../design/architecture-foundation_pack.md)。

## 运行实例

日常客户端实例由 Loom 管理，启动命令从仓库根目录执行：

```powershell
.\gradlew.bat :version:26.3-fabric:runClient
```

需要测试加入多人游戏时，可以启动 `runClientMultiplayer` 并连接独立服务器。Vulkanite 是客户端模组，独立服务器不会加载或运行产品逻辑；产品行为的本地检查使用 `runClient`。自动化验证按各自 `validations/<name>/validation.md` 中的命令运行，使用对应验证目录的专属实例。不要把 Prism 人工实例用作自动化环境。

## 检查

格式修改后运行 `spotlessApply`，再运行 `check`：

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat check
```

`check` 包括合规规则、配置校验、Markdown 与源码格式、项目组件的检查，以及 `26.3-fabric` 源码和 `probe` 的编译。影响发行 JAR 时，再运行 `verifyRelease` 核对元数据、许可文件、`product` 组件内容及 probe 隔离。合规检查的用法见[合规检查](testing-compliance.md)。

## 常用命令

命令从仓库根目录执行；Unix 使用 `./gradlew`。

| 命令 | 作用 |
| --- | --- |
| `.\gradlew.bat :version:26.3-fabric:runClient` | 启动产品客户端实例 |
| `.\gradlew.bat :version:26.3-fabric:runClientMultiplayer` | 启动第二个客户端实例，用于多人连接验证 |
| `.\gradlew.bat check` | 运行合规、配置、格式、组件检查并编译 Target 与 probe |
| `.\gradlew.bat checkRepository` | 不编译 Target、不读取本地输入的仓库检查，供持续集成使用 |
| `.\gradlew.bat verifyCompliance` | 只运行合规规则；加 `--strict` 时提示也视为失败 |
| `.\gradlew.bat verifyConfiguration` | 只校验仓库配置文件 |
| `.\gradlew.bat verifyInputs` | 核对本地输入路径与哈希 |
| `.\gradlew.bat lintMarkdown` | 运行 Markdown 检查 |
| `.\gradlew.bat spotlessApply` | 格式化支持的源码与文档 |
| `.\gradlew.bat verifyRelease` | 构建并检查发行 JAR |
| `.\gradlew.bat :version:26.3-fabric:runValidation<Name><Role>` | 启动一项验证的角色；`<Name>` 与 `<Role>` 是验证名和角色名的大驼峰写法，实际命令以对应 `validation.md` 为准 |

## 完成改动

按 W-01 同步依赖说明、玩家文档与项目事实。完成前运行 `spotlessApply` 和 `check`；改动影响发行 JAR 时也运行 `verifyRelease`。行为改动按相应验证的通过标准确认，涉及游戏画面的结果与自动化证据分开报告。
