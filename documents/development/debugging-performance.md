# 性能诊断

详细性能诊断默认关闭。产品保留区段生命周期与 BLAS 计数；正常游玩不记录帧间隔，不调用 CPU 性能计时，也不创建或写入性能查询。通过 JVM 参数 `-Dvulkanite.profile=cpu|gpu|all` 按需采样；`off` 关闭。这些开关不改变光影质量。

## 采样控制

验证探针提供 `profile <令牌> off|cpu|gpu|all` 与 `resetperf <令牌>` 命令，模式切换与窗口重置在渲染线程执行。正常客户端不加载探针，也不监听控制端口。隔离对照的运行方法见[诊断验证](../../validations/performance-diagnostics/validation.md)。

GPU 默认每个命令池每 32 次有计时范围的命令录制采样一次；`-Dvulkanite.profile.gpuInterval=<间隔>` 可设置 1 到 4096。每个命令池最多持有八个查询租约，只有 GPU 安全回收命令后才复用查询。读取不使用 WAIT，未就绪与错误单独计数；缺失样本不报告为零耗时。重置窗口后，旧租约结果不能进入新窗口。

OpenGL 时间戳使用同一采样间隔，最多八组租约。只在查询结果可用后读取或复用；窗口重置会丢弃旧结果，管线销毁时释放查询。关闭诊断时不创建或写入查询。完成条件依据 [ARB_timer_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt)，不为采样增加 `glFinish` 或队列空闲等待。

## 范围

| 类型 | 字段 | 测量范围 |
| --- | --- | --- |
| CPU | `IndexedFence` | 等待 OpenGL 索引绘制副本完成 |
| CPU | `IndexedReadback` | 读回缓冲与组装公开绘制记录 |
| CPU | `Assemble` | 几何转换与场景组装 |
| CPU | `TextureBridge` | 共享纹理首次导入或内容更新 |
| CPU | `TerrainCopy` / `TerrainUpload` | 复制 Sodium 区段输入与排队上传 |
| CPU | `UniformWait` | 获取帧 uniform 租约，包括已有的等待 |
| CPU | `Tlas` | 主机侧构建、准备与提交加速结构 |
| CPU | `Pipeline` | composite 边界内 Vulkan 管线主机工作 |
| CPU | `InteropSignal` | OpenGL 信号提交与已有的 flush 调用 |
| CPU | `InteropWait` | OpenGL 等待信号的提交与已有的 flush 调用，不代表 GPU 等待时间 |
| CPU | `Retire` | 主机侧检查已提交帧的完成状态与运行回收回调 |
| GPU | `Ray` | 包声明的全部光追阶段与阶段间依赖 |
| GPU | `Tlas` | 顶层加速结构构建命令 |
| GPU | `DynBlas` | 动态几何上传、屏障与 BLAS 构建命令段 |
| GPU | `TerrainBlas` | 地形 BLAS 批次构建命令 |
| GPU | `GlScene` | Iris 世界绘制开始到 Vulkan 交接前的 OpenGL 时间戳区间 |
| GPU | `GlHandoff` | OpenGL 交接前到等待 Vulkan 输出完成后的时间戳区间，包含 Vulkan 工作 |

## 解读限制

CPU 范围可能嵌套，不能相加；CPU 等待表示对 GPU 的依赖，不等于 CPU 计算工作。GPU 范围使用 ALL_COMMANDS 时间戳，包含对应命令段的依赖等待，不是独占硬件执行时间。地形批次大小与动态几何复用会改变样本数量。纹理桥接未覆盖全部 OpenGL/Vulkan 互操作耗时。

OpenGL 区间还可能包含 CPU 提交间隙与 GPU 依赖等待；`GlHandoff` 不能全部归因于互操作损耗。OpenGL 与 Vulkan 没有做时钟校准，不能用两侧原始时间戳计算单程延迟；各范围也不能直接相减得出纯交接开销。

`frameCount` 每次 PT 执行入口计数，表示回调次数，不是 swapchain present。吞吐量必须从窗口内计数差计算；帧间隔数组只保留最后 512 条。停在菜单、暂停、失去焦点或改变光影设置都会影响结果。对照先保持视角、世界、分辨率与采样参数相同，再比较关闭和开启诊断的多个窗口。
