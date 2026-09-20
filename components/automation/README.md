# 自动化工具

本组件提供隔离客户端的空区段验证、性能采样与 Windows 环境预检。游戏内控制入口属于独立的 `vulkanite_probe` 模组，仅在验证运行加载；产品诊断保留在客户端源码中。

命令与通过标准见[空区段验证](../../validations/integration-empty_section/validation.md)和[性能诊断验证](../../validations/performance-diagnostics/validation.md)。驱动只操作各验证自己的实例与结果目录，不读取日常实例或 Prism。

区段计数不能证明某一坐标的 holder 唯一性。性能驱动报告 PT 回调吞吐量、CPU 范围和低频 GPU 查询；详细限制见[性能诊断](../../documents/development/debugging-performance.md)。工具的 `check` 执行 Python 采样统计测试。

性能驱动支持 `-WorldName` 选择本验证实例中已存在的存档，用于相同主世界的前后对照。这不会读写日常实例，也不会对所选世界执行空区段 fixture 的方块变更。
