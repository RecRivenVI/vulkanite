# Vulkanite 线程交接文档（给下一任模型）

生成时间：2026-09-30（本线程收束时）  
工作区：`D:\Workspaces\Repositories\GitHub\RecRivenVI\vulkanite`  
远程：`https://github.com/RecRivenVI/vulkanite.git`（`origin`）；上游 `MCRcortex/vulkanite`

**硬约束（用户明确要求，交接后仍须遵守）：**

- **不要** `git add` / `commit` / `push`，除非用户再次明确要求  
- **不要** 重置/清理当前 dirty 工作区  
- **不要** 代替用户启动/控制/关闭 Prism 人工实例  
- 自动化只在 **repo-local** `run/automation/`，禁止把 `E:\Minecraft\PrismLauncherDev\instances\Vulkanite 26.3-fabric` 当自动测试环境  
- 性能优化不得靠降低分辨率 / SPP / MAX_BOUNCES / 视距 / 删几何 / 关 sun·moon·cutout 等换 FPS  
- 正确性优先；诊断/审计不进「正式发行功能」

---

## 1. Git / 工作区

| 项 | 值 |
|---|---|
| HEAD | `c4fad780e725f64f38b83e7a7d1d03ee211a68fd`（`Update (MiMo V2.6 Pro)`） |
| 与 origin | 同步（此前已推送过该 commit） |
| 工作区 | **大量未提交修改**（见下） |
| staged | 无 |

### Dirty（勿清理）

**产品 / pack / 基础设施（未提交）：**

- `build.gradle` — Loom `runAutomation`（runDir + audit env / quickPlay）
- `packs/Vulkanite-Foundation/shaders/gbuffers_{terrain,textured,block,entities,hand}.fsh` — cutout discard；hand 另有 `gl_FragDepth=0`
- `gbuffers_hand.vsh` — `texCoord`
- `ray0.rgen` / `ray0_0.rahit` / `ray0_1.rahit` — 阴影 ray：`TerminateOnFirstHit|SkipClosestHit` + any-hit 写 `hit.distance`
- `src/.../audit/`（新）— `Diagnostics`、`AuditEntrypoint`（门控 IPC）
- `src/.../lib/other/GpuTimestamps.java`（新）
- `AccelerationManager` / `AccelerationTLASManager` / `EntityBlasBuilder` / `VulkanPipeline` / `Vulkanite` / `ProducedSceneAssembler` / `PublicIndexedDrawCapture` / `MixinChunkBuildResult` / `MixinMinecraftClient` / `fabric.mod.json`
- `tools/automation/**`（新）— 打包/启动/ bench 脚本

**最后已知 jar SHA-256（`build/libs/vulkanite-0.0.4-pre-alpha+26.3.jar`）：**  
`E938BAB061132368599B1C1B61EC422AE746CD5752AC3F7B23D5873F755033A3`

**Prism 用户实例内 JAR（人工验收用，可能落后）：**  
曾为 `C680B965…` 或更早；**不要**把带 audit 的 automation jar 当发行候选直接投放，除非用户要求。

---

## 2. 项目定位（速览）

Vulkanite = Minecraft **26.3** Fabric 客户端模组，在 Iris COMPOSITE 边界插入 **一段** Vulkan 路径追踪。

- MC 26.3 / Fabric Loader 0.19.5 / Sodium 0.9.2 / Iris 1.11.6 / Java 25 / LWJGL 3.4.3  
- Iris/OpenGL 管窗口与 composite；Vulkanite 捕获地形+动态几何 → BLAS/TLAS → ray dispatch  
- Pack 契约：`vulkanite.properties` schema 2；`ray{N}.rgen` 等；空 map=已确认空几何，null=未捕获  
- Foundation 参考包：SPP/Bounce 可调、cutout 0.5、日月直接光、OpenDRT 显示

架构细节见 `docs/architecture.md`、`docs/shader-pack-interface.md`。

---

## 3. 本线程已完成工作（按主题）

### 3.1 空区段残留（正确性）

- **现象：** section 最后一个方块被破坏后 RT 几何残留  
- **根因候选（已修路径）：** Sodium `LevelSlice.prepare` 对 `hasOnlyAir` 返回 null → `createRebuildTask` null → `submitSectionTask` 直接构造 `BuiltSectionInfo.EMPTY` + `emptyMap` 的 `ChunkBuildOutput`，**不进** `ChunkBuilderMeshingTask.execute`，故无 generation/geometry 捕获  
- **修复：** `MixinChunkBuildResult.<init>` TAIL：`info==EMPTY && meshes!=null && meshes.isEmpty()` → `geometryMap=Map.of()` + 构造时 generation；空 map≠null（null=未捕获）  
- **状态：** 已在自动化 empty-section E2E 上 **PASS**（empty update、removeSection、TLAS 计数升降、再放置、快速往返）  
- **注意：** `removeSection==false`（无 holder）**不得**单独判 FAIL  

### 3.2 切线崩溃

- **现象：** `IllegalStateException: Normal producer tangent collapsed in world space`  
- **修复：** `ProducedSceneAssembler.transform` 退化时丢 `TANGENT_PRESENT` + 零切线，不抛帧  
- **状态：** 已实现；曾部署过含此修复的 jar  

### 3.3 Cutout / 深度 / 手

- **现象：** 光栅自定义内容按深度叠加，被树叶/草 **cutout 镂空** 误挡  
- **修复：** Foundation `gbuffers_*` 在写深度前 `alpha<0.5 discard`（与 PT rahit 一致）  
- **手：** cutout 不写深度；实体像素 `gl_FragDepth=0` 置顶（避免挡住/被挡）  

### 3.4 自动化基础设施（Phase A）

**边界：**

| 环境 | 路径 | 用途 |
|---|---|---|
| **实验室** | `run/automation/**` | Agent 自动测试 |
| **人工验收** | `E:\Minecraft\PrismLauncherDev\instances\Vulkanite 26.3-fabric\minecraft` | 仅部署 JAR/pack + 读日志；**不代启** |

**已落地：**

- Loom `runAutomation` → `run/automation/empty-section/minecraft`  
- Win32 Desktop：`Launch-OnWin32Desktop.ps1`（`CreateDesktop` + `CreateProcess lpDesktop=WinSta0\<name>`）  
- 门控 audit：`-Dvulkanite.audit.enable=1` + token + `127.0.0.1`；`ping/snapshot/frames/resetperf/setblock/time/quit`  
- `Diagnostics`：空更新、removeSection、BLAS 计数、CPU span、帧环  
- **empty-section E2E PASS** 证据目录示例：  
  `run/automation/runs/empty-section-e2e-final/`  

**已废弃：** Prism `Vulkanite-Auto` 实例作为自动化执行环境。

### 3.5 性能（Phase B，第一轮，未完成净收益证明）

**用户 Prism 基线（历史，勿当自动化数字）：**  
OFF 800–900 / A-NoRT 600 / B-NoScene 270 / C1 80 / C2 66 / C3 37 FPS  

**repo-local C2 自动化：** 稳定 **~30–34 FPS**（`frameCount/wall`），CPU 合计约 2–7ms/帧 → **GPU/同步主导**。  

**已做优化（语义保持）：**

1. 阴影 ray flags + any-hit 记 hit  
2. 动态 BLAS 字节未变则复用  
3. 无 cutout/blend 时实体 BLAS `OPAQUE`  

**结果：** 整帧 FPS **无稳定净提升**（环境疑似钉在 ~34）。  

**GPU timestamp 未生效**（恒 0）；`WAIT_BIT` 在 fence 回调会 **卡死** 游戏（已撤回）。

**Profiler 挂点：** `PublicIndexedDrawCapture.finish` fence/readback、`ProducedSceneAssembler.assemble`、`VulkanPipeline`、`AccelerationTLASManager.buildTLAS`；`Mix` `renderTick`。

---

## 4. 关键实现位置（接手时优先读）

```
mixin/sodium/chunk/MixinChunkBuildResult.java   — 空快捷路径
compat/SodiumSectionInput.java                  — empty vs null
acceleration/AccelerationManager.java           — empty→removeSection + Diagnostics
acceleration/AccelerationTLASManager.java       — entity BLAS reuse / TLAS
acceleration/EntityBlasBuilder.java             — OPAQUE / any-hit
client/rendering/interop/ProducedSceneAssembler.java — tangent / assemble
audit/Diagnostics.java + AuditEntrypoint.java   — 只读计数 + IPC
lib/other/GpuTimestamps.java                    — GPU ts（当前无效）
tools/automation/bin/*.ps1, bench_sample.py
```

---

## 5. 环境与命令

```text
构建:     gradlew.bat jar --console=plain --no-daemon
自动化:   set VULKANITE_AUDIT_* ; gradlew.bat runAutomation
隔离启动: tools/automation/bin/Launch-OnWin32Desktop.ps1
bench:    tools/automation/bin/bench_sample.py
empty E2E: tools/automation/bin/Invoke-EmptySectionE2E.ps1
```

Prism CLI（仅人工实例，**勿自动 launch**）：`E:\Minecraft\PrismLauncherDev\prismlauncher.exe`  
AGENT_LOCK：`E:\Minecraft\PrismLauncherDev\.agent-locks`、`.agent-runs`（与 Radiance 共用约定，见 `AGENT_GUIDE.md`）

---

## 6. 已知问题 / 未完成

| 优先级 | 项 |
|---|---|
| P0 | **性能净收益未证明**；先修 GPU timestamp 或 fence→fence GPU 段，再做真实 A/B |
| P0 | **Desktop 隔离下 FPS 校准**（~34 vs 用户 Prism 66）；未校准前不要把 auto 数字当正式 baseline |
| P1 | empty-section / 切线 / cutout·手深度：**当前 dirty 构建**需再跑一轮 full regression |
| P1 | 输入隔离：desktop+IPC-only 已具备；**Default 桌面键鼠挑战未自动化** |
| P2 | `GpuTimestamps` 未写入 snapshot 或不生效 |
| P2 | 退出时 Vulkan device 不销毁；退出挂起主因曾判定为 DH 非 daemon 线程（非 Vulkanite） |
| P2 | 34 FPS 可能来自 `inactivityFpsLimit`/隔离 DWM/场景差——**未定论** |

**Rejected（勿重复）：** fence 回调 `vkGetQueryPoolResults(WAIT_BIT)` → 死锁。

---

## 7. 建议下一步（给下一任）

1. **修 GPU 时间戳**（fence 后读或 availability，不阻塞）并进 `Diagnostics.snapshot`  
2. **校准 FPS**：关 AFK/失焦限帧、对比 Prism 同场景；建立 benchmark preflight  
3. 若确认 GPU ray 主导：审 shadow/any-hit/payload（禁止降 SPP 语义）；若 BLAS 主导：本地空间+变换或 skip unchanged topology（禁止假缓存动画）  
4. 每项优化：build → empty-section 回归 → C2 A/B → 无收益则回退  
5. 有净收益后再考虑投放 **无主动 audit** 的人工候选到 Prism（等用户自启）

---

## 8. 验证状态（勿夸大）

| 项 | 状态 |
|---|---|
| empty-section 自动化 | **PASS**（某次 E2E；此后又有性能改动） |
| 切线 fallback | 实现过；当前包未复测 |
| cutout/手深度 | 实现过；视觉多为用户反馈，非完全自动断言 |
| C2/C3 性能净收益 | **未证明** |
| GPU 分段计时 | **未成功** |
| Prism 人工验收 | 部分历史；当前 dirty 构建未正式投放验收 |

---

## 9. 一句话交接

工作区在 `c4fad78` 上叠了 **空区段修复验证、切线降级、Foundation 深度/cutout/手、repo-local 自动化+audit、性能 profiler 与三项语义保持优化**；自动化能跑通 empty-section，但 **性能仍是 GPU/同步主导且第一轮无净收益**，下一步应先打通 GPU 测量与 FPS 校准，再继续优化。**不要提交、不要动 Prism、不要清 dirty。**
