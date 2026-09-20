# Vulkanite 配置示例

Vulkanite 的参考 Target 为 `26.3-fabric`。通用配置定义见[配置参考](../../gradle/conventions/specification/reference/configuration-repository.md)，开发环境见[开发环境](../development/setup-vulkanite.md)。

## 日常实例

在 `local.toml` 中只覆盖本机设置；例如只为参考 Target 的客户端设置内存：

```toml
[targets."26.3-fabric".client]
memory-max = "6G"
```

配置错误中的表路径使用同一写法，例如 `instances.toml [targets."26.3-fabric".client.memory-max]`。

## 验证 Target

验证的 `targets` 数组使用已登记的 Target；工具名取自项目实际登记的 `tool` 组件。例如：

```toml
tool = "automation"
targets = ["26.3-fabric"]
```
