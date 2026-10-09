# 光影包接口

Vulkanite 26.3 允许光影包在指定的 Iris **COMPOSITE** 阶段之后请求一段 Vulkan 光线追踪执行。Iris 先完成该阶段的 compute dispatch、屏障和可选光栅绘制；随后 Vulkanite 执行光线追踪，并在 Iris 继续后续 composite 阶段与标准 final 阶段前把图像交回 OpenGL。没有光线追踪阶段和 Vulkanite 声明的光影包继续使用 Iris 的 OpenGL 路径。

光影包必须提供 `shaders/vulkanite.properties`，以及连续编号的 `ray0.rgen`、`ray0_0.rmiss` 和 hit shader。当前接口不兼容旧 sidecar 或 scene ABI。最小声明如下：

```properties
schema=2
sharedColorTargets=1
sceneGeometry=true
execution.phase=COMPOSITE
execution.after=composite
color.read=
color.write=0
storage.12.minimumBytes=512
```

`execution.after` 必须准确命名一个活动的 Iris composite 阶段，包括仅执行 compute 的阶段。`sharedColorTargets` 分配从零开始连续的一段共享 `colortex` 目标；它**不代表**光线追踪 shader 可以写入这段中的所有目标。`color.write` 选出一个逻辑目标。Vulkanite 把该目标当前的 main 或 alt 图像绑定到 set 0、binding 6 的单个 `rgba32f` storage image，并按该图像尺寸执行追踪。空的 `color.read` 表示没有颜色输入。非空的逗号分隔列表按声明顺序，将对应的当前物理图像绑定到 set 0、binding 18 的 combined-image-sampler 数组，数组长度与列表一致。当前接口拒绝对同一个逻辑目标原地读写。

光影包还必须把每个 `storage.N.minimumBytes` 声明为 Iris 的 `bufferObject.N`；相应的 ray SSBO binding 必须存在，且共享分配在 dispatch 前达到声明的容量。当前每个 ray 阶段只支持一个仅含 SSBO 的 descriptor set。

逻辑 `colortex` 目标对应的物理 main/alt 图像取自指定阶段完成后的 Iris 当前 ping-pong 状态，不能按该目标过去是否翻转过来决定。光影包可以在 Vulkan 阶段之后再安排 Iris composite 阶段；后续阶段能在同一帧读取 Vulkan 结果。指定阶段缺失或执行两次时，Vulkanite 报告明确的接口错误。光影包作者必须声明可写 Iris 目标的格式，使其匹配 ray shader 的 storage-image 格式，并确保后续阶段或 final 采样所选逻辑输出。

## 相机与场景数据

Set 0 binding 0 是 160 字节的 std140 主机相机块：四个 `vec3` 光线角点位于字节 0–63，`mat4 inverseView` 位于 64–127，`uint frame` 位于 128，`uint flags` 位于 132，填充至 144，`uvec4 vulkaniteFrameAbi` 位于 144–159。ABI 版本为 3。Vulkanite 只提供生成光线与场景世界坐标所需的相机数据。光影包定义的 Iris uniforms（包括自定义值）可以由普通的 composite compute 阶段写入已声明的 SSBO。该 SSBO 的字段布局与有效性检查由光影包负责；Vulkanite 校验 binding 和容量，不解释私有字段含义。

`sceneGeometry=true` 包含普通 Sodium 地形与通过 Iris/RenderPearl 提交的受支持世界绘制。Set 0 binding 1 是场景 TLAS；set 1 binding 0 是当前几何 storage array。地形仍按既定四边形绕序使用每顶点 64 字节的数据。动态场景顶点是每顶点 112 字节、未索引的三角形列表。Iris 四边形展开为 `(0,1,2)` 与 `(0,2,3)`；索引三角形绘制遵循生产者实际的索引顺序与 base vertex。Set 0 binding 7 是含 256 个条目的动态 albedo 数组；binding 16/17 的 normal/specular 数组可选。

| 字节偏移 | 动态顶点字段 |
| --- | --- |
| 0、12、16、24 | 世界坐标 `vec3`、RGBA8 颜色、UV0 `vec2`、世界法线 RGBA8_SNORM |
| 28、32、36 | 采样纹理槽 `uint`、表面标记 `uint`、选定材质 ID `int32` |
| 40、48、56 | UV1 与 UV2（各两个有符号 32 位分量）、`mc_midTexCoord` `vec2` |
| 64、68、84 | 世界切线 RGBA8_SNORM、四个原始 `iris_Entity` 有符号 32 位分量、两个原始 `mc_Entity` 有符号 32 位分量 |
| 92、96、100–111 | 原始 `at_midBlock` RGBA8_SNORM、presence mask `uint`、保留零值 |

颜色和位置使用生产者实际绘制的数据；法线和切线都映射到世界空间。法线使用逆转置变换，切线使用正向线性变换（第一人称手部使用观测到的投影 Jacobian），切线再对法线正交化；镜像变换会翻转切线手性。动态表面标记中，bit 0 表示摄像机隐藏的身体，bit 1 表示第一人称模型，bit 2 表示 cutout coverage，bit 3 表示粒子，bit 4 表示双面表面，bit 5 表示 alpha blend。选定材质 ID 是完整的有符号 32 位便利值；原始 Iris/MC 通道仍可供光影包策略读取，包括映射结果为零或映射未命中的情况。

Presence bits 0–6 分别标记 UV1、UV2、midTexCoord、切线、iris_Entity、mc_Entity 与 midBlock；bits 7–13 区分这些字段是否来自 GL 常量；bits 14–17 标记颜色/法线是否存在及其常量来源。presence bit 未设置时，零值**不代表**生产者提供的值。Iris 第四通道按原始输入保留，不赋予语义。

地形顶点在字节 16 携带 Sodium 完整打包的 `a_LightAndData`（低两个字节是 lightmap U/V，其余生产者数据位保留）；字节 20 在 Iris 提供时携带来源 mid-UV，否则携带生成的中点；字节 24/28 分别是派生切线/法线；字节 32 是解码后的 `mc_Entity`；字节 36 在可用时携带来源 `at_midBlock`。规范四边形的字节 40/44 是 `layerStartWords`/`layerCount`，表面标记位于 48（bit 1 双面、bit 2 cutout、bit 3 alpha blend），有效性/来源位于 52，精确打包的 Iris `iris_Normal` 来源位于 56，在可用时原始打包的 Sodium/Iris `mc_Entity` 来源位于 60。

地形 presence bits 0–7 分别标记来源光照、来源 mid-UV、派生 mid-UV、派生切线、派生法线、来源 `mc_Entity`、来源 `at_midBlock` 与来源 `iris_Normal`。Bit 8 标识实际 Sodium Compact 顶点类型：只有设置该 bit 时，`a_LightAndData` 的字节 2 才携带 `Material.bits`（`mipped | alphaCutoff.ordinal << 1`）；在 Iris XHFP 中，该字节改为携带切线符号。XHFP 活动时，参考光影包不会虚构 Compact cutoff bits。光影包可以检查这些输入，但不能假定缺失的可选字段含有有效零值。

只有四角顺序相同、打包位置完全一致且朝向法线相同的四边形，才能组成共面层组。组顺序遵循 Sodium 标准 pass 顺序（solid、cutout、translucent），组内按各 pass 原有四边形顺序；未知 pass 保持独立。`layerCount=0` 表示规范四边形本身是唯一表面。多层组中，`layerStartWords` 指向同一受控几何缓冲区里追加的连续列表，列表包含 `layerCount` 个**完整的原始四边形**，每个顶点 64 字节；只有规范四边形贡献 BLAS 三角形。参考光影包按顺序使用每层来源纹理 alpha 与顶点颜色合成。它不按颜色把草或玻璃分类，每层原始属性都可以访问。

公开 indexed-draw 适配器接受满足支持条件的 OpenGL RenderPearl 三角形提交：Position/UV0 顶点属性、`Sampler0` 与标准 `DynamicTransforms`。适配器读取**实际后端管线**与创建 VAO 时使用的绑定，包括来自逐绘制 GL 常量而非 VBO 字节的属性。即使活动 shader 优化掉某顶点字段，只要其 stride 与活动 Position 绑定匹配后端，且字段字节经确认并非填充值，仍可从生产者完整的 VertexFormat 读取。

适配器在生产者缓冲仍有效时，把每个版本的生产者 VBO、每段被引用的索引切片和逐绘制变换复制到 Vulkanite 自有 staging；CPU 解码前会等待有界的帧级 fence。RenderPearl 的写入会让复用的 VBO 快照失效。适配器检查每个无符号索引与 `baseVertex`，再构建自有的世界空间三角形。主场景与普通阴影提交分别使用各自 pass-view 矩阵和未偏移的相机原点；主场景提交优先于已证明匹配的阴影提交，不匹配的阴影几何仍可用。上游从未提交的几何无法获得。适配器不会从原始字节推断任意顶点 shader 位移、自定义变换语义、非三角形图元，或没有受支持复制路径的私有 GPU 缓冲。材质映射 ID 不是物体身份；没有有效来源 ID 时，光影包应使用中性处理。

对于受支持的 2D 表面，适配器为每个材质槽保留生产者 `Sampler0` 的 repeat/clamp、nearest/linear min/mag、mip LOD 与受支持的 anisotropy，作为自有 sampler 状态。Sodium 方块图集及其可选 PBR 图集视图遵循同一规则，使用真实图集 sampler。纹理视图必须覆盖完整的底层 2D 纹理；部分 mip 视图、数组、立方体贴图与不支持的纹理格式会明确报错，不会按另一种图像处理。声明的 `color.read` 图像使用 Vulkanite 固定的 nearest/clamp sampler；光影包可用 `texelFetch` 读取精确像素。

参考光影包分别处理 cutout 与 alpha blend：cutout 使用固定覆盖阈值，alpha blend 使用按来源 alpha 比例随机覆盖。这只是路径追踪近似，不等同于自定义混合方程、折射或有序透明表面。

`sceneGeometry=false` 会关闭普通场景捕获，但仍使用该 ray 阶段执行器及已声明的颜色/存储资源；它不是 Iris compute shader 的通用替代方案。该接口不包含 DLSS、时域重建、SHaRC、外部网格发布 API 或旧的刚性介质协议。
