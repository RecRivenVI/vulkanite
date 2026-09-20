# Reconstruction ABI v1 / v2

ABI v2 adds full-float color input/output for physical daylight-to-night dynamic
range. Binding 8 and the host reconstruction output use RGBA32F in v2; all other
resource/camera conventions are unchanged. ABI v1 retains RGBA16F. Current
Foundation declares v2 and must be paired with a host supporting it.
The native bridge now exposes its ABI version (2); stale bridges are rejected
as an unavailable optional capability before any incompatible JNI payload is sent.

Vulkanite owns the reconstruction service: render/output extents, guide images,
history, jitter, exposure and output handoff. Input production is represented by
`ReconstructionInputProvider`; Foundation currently uses the Vulkan ray-tracing
provider. NGX is one backend below that service rather than the pack interface.
This separation is the basis for a later Iris/OpenGL input provider without moving
presentation away from the existing OpenGL window. A pack supplies scene-linear
lighting and geometric guides. Reconstruction executes after the ray pass, before
Iris tone mapping and the existing OpenGL UI/presentation. This interface does not
implement frame generation.

## Installation

The initial native backend targets Windows x64. Build the mod with Java 25 and
build the bridge with CMake, Visual Studio C++ tools, Vulkan SDK and NVIDIA's official
[DLSS SDK](https://github.com/NVIDIA/DLSS). Tested SDK commit:
`374959484e79a640feaba44c93ac8cfb0a03f5b5`; runtime reports 310.9.1.

`tools/Build-DlssBridge.ps1` takes `-NgxSdk`, `-BuildDirectory`, `-InstallDirectory`,
and optional `-JavaHome`, `-VulkanSdk`, `-Generator`. Use a scratch build directory
and the game's `config/vulkanite/dlss` directory as installation destination.
It builds `vulkanite_dlss.dll` and copies official `nvngx_dlss.dll` and
`nvngx_dlssd.dll` there. These binaries are not embedded in the mod JAR or shader pack.
Preserve and follow the SDK's redistribution terms for any distributed bundle.
Quit the client before replacing installed libraries.

`vulkanite.dlss.directory` overrides the installation directory;
`vulkanite.dlss.disabled=true` forces the optional capability off. Missing native
libraries, unsupported capability, creation or evaluation failures log the reason
and retain pack raw-color output. Full-resolution rendering resumes at the next
safe frame boundary after an evaluation failure. No driver/software installation
or library download happens automatically.

## Pack declaration

Declare these integers in one ray generation shader (the current Vulkan RT input
provider supports one ray pass). Macros and conditional branches are resolved before parsing:

```glsl
const int vulkaniteReconstructionVersion = 2;
const int vulkaniteReconstructionMode = 2;
const int vulkaniteReconstructionQuality = 2;
const int vulkaniteReconstructionOutput = 0;
const int vulkaniteReconstructionExposure = 8; // optional in v2
```

Mode: 0 raw, 1 Super Resolution, 2 Ray Reconstruction. RR includes reconstruction
to display resolution; it is not followed by another SR pass. Quality uses NGX's
enum: 0 performance, 1 balanced, 2 quality, 3 ultra-performance, 4 ultra-quality
(runtime-dependent), 5 DLAA. Output selects an existing Iris color target, 0–15.
Its dimensions are the requested display extent; NGX chooses the input extent.
The optional exposure target is an existing 1x1 RGBA32F Iris target. Its R channel
contains previous-frame log2 pre-exposure and A=1 marks valid history; invalid
history uses gain 1. The host reads its four floats after the existing GL completion
boundary, clamps EV to -8..24, supplies gain/inverse gain in the camera UBO, and
passes the same gain as NGX `InPreExposure`. This deliberately synchronous alpha
path adds a tiny readback; future asynchronous presentation needs a separate contract.
Foundation exposes modes and common quality settings through Iris shader options.
For diagnostics only, JVM properties `vulkanite.dlss.mode` and
`vulkanite.dlss.quality` override the pack request at pipeline creation.

All eight storage images below must be declared, bound and written over every
dispatched pixel, including background pixels. The host dispatch dimensions are
the input extent. The host blits reconstruction (or raw color) into the requested
Iris target. Packs must not apply a second tone map, spatial upscaler or denoiser
before RR. Existing output binding 6 is optional for a reconstruction pass.

| Set 0 binding | Format | Meaning |
| --- | --- | --- |
| 8 | rgba32f (v2), rgba16f (v1) | Noisy HDR linear color times host pre-exposure, before tone mapping |
| 9 | r32f | Reverse-Z hardware depth in [0,1], near 1 and far/sky 0 |
| 10 | rg32f | Current-to-previous motion, input-pixel units, excluding jitter |
| 11 | rgba16f | Linear diffuse albedo |
| 12 | rgba16f | Linear specular albedo |
| 13 | rgba16f | World-space unit normal in RGB [-1,1], roughness in A [0,1] |
| 14 | r32f | Specular ray hit distance; 0 when no specular lobe |
| 15 | r8 | Bias current color / invalid-history mask [0,1] |

Image Y follows the ray grid and current Iris/GL output orientation. Motion is
`(previousNdc.xy-currentNdc.xy)*0.5*inputSize`, with no arbitrary Y flip.
Primary lighting, depth and material guides must describe the same jittered ray.
Foundation uses diffuse Lambert surfaces and ideal water/clear-glass dielectrics;
its specular guides describe the actual material and reflected first-hit distance.
SR uses color/depth/motion/bias; it does not denoise the path tracer like RR.

## Camera and history

The original 176-byte camera UBO prefix is unchanged. New std140 fields:

The previously unused `moon.w` at byte 156 now carries relative lunar irradiance
from the game's moon phase (0 new moon, 1 full moon, Lambert phase curve).

| Byte offset | Type | Meaning |
| --- | --- | --- |
| 176 | mat4 | Previous unjittered world-to-clip |
| 240 | mat4 | Current unjittered world-to-clip |
| 304 | mat4 | Current world-to-view |
| 368 | mat4 | Current view-to-clip, reverse-Z [0,1] |
| 432 | vec4 | Ray jitter XY in input pixels, input width/height |
| 448 | vec4 | Output width/height, reset (0/1), native enabled (0/1) |
| 464 | vec4 | Pre-exposure gain, inverse gain, two reserved zeros |

Without an exposure target the gain is 1. Guides/albedos are never exposure-scaled.
The pack must multiply radiance by the supplied gain and remove that same frame's
gain before scene-referred metering/display. Foundation records the applied EV in
its meter's B channel, meters unexposed luminance, then applies current display
exposure. This prevents a positive exposure feedback loop and double application.

The host converts Iris's conventional OpenGL projection to reverse-Z [0,1];
do not use Iris's unconverted projection for binding 9. Jitter is a 32-frame
Halton sequence, applied to primary ray sampling, excluded from motion, and
passed with the corresponding opposite sign to NGX. Sky motion uses direction
with homogeneous W=0, excluding camera translation. Entity previous positions
come from Scene ABI v4; invalid correspondence sets binding 15 to 1.

History resets on pipeline/reload/extent changes, world/camera-entity changes,
camera jumps over 32 blocks, large projection changes or a frame gap over one second.
Smooth FOV changes and view bobbing retain history and are represented in motion.
Resources and native features retire after GPU completion. The existing single
in-flight frame and GL/Vulkan semaphore handoff remain in force. Only native guide
images enter NGX; UI never enters these inputs. Existing GUI separation is retained.

## Validation scope

`tools/Validate-Foundation.ps1` covers shaders, ABI reflection, active macro branches,
depth projection, and entity correspondence across reordering/topology/world changes.
Actual NGX evaluation is also required; compilation alone is insufficient.
Visual acceptance must cover camera motion, animated entities, disocclusion,
resizing, pack reload and world changes. This alpha still has the Foundation scene
coverage limitations and large-coordinate precision limits documented separately.
