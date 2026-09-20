# SHaRC service ABI v1

Vulkanite owns the lifetime and synchronization of a Spatially Hashed Radiance
Cache. Shader packs opt in and supply cache Update samples and Query calls from a
Vulkan ray-generation shader. Vulkanite runs Resolve after the ray dispatch so
the newly accumulated signal becomes visible on the following frame.

The implementation vendors NVIDIA SHARC 1.8.3 shader sources under its NVIDIA
RTX SDK license. The upstream headers (with pack-relative include paths where
needed) are present in both the runtime shader resources and Foundation pack;
`third_party/sharc/LICENSE.md` contains the
governing license. SHaRC is shader-only and does not require an NVIDIA GPU.

## Pack declaration

The expanded ray-generation source declares:

```glsl
const int vulkaniteSharcVersion = 1;
const int vulkaniteSharcEnabled = 1;
const int vulkaniteSharcCapacityPower = 20;
```

Capacity is `2^capacityPower` entries. ABI v1 accepts powers 16–24. Foundation
defaults to `2^20`, using approximately 36 MiB: 4 bytes per compact hash key,
16 bytes per current-frame accumulation entry, and 16 bytes per resolved entry.
All buffers are device-local, zeroed before first use, and carry shader device
addresses. The JVM property `vulkanite.sharc.capacityPower` can override the pack
request for diagnostics.

ABI v1 fixes the upstream feature choices to a compact 32-bit hash, no responsive
lighting and no directional SH encoding. These choices keep the first integration
small and avoid 64-bit atomic requirements. They are versioned choices rather than
limitations of the host resource manager.

## Camera UBO extension

The fields below follow Reconstruction ABI v2's byte 464 `radianceExposure` field.
They are valid only when the host constructed a SHaRC service for the current pack.

| Byte offset | Type | Meaning |
| --- | --- | --- |
| 480 | uvec2 | Hash-entry buffer device address, low/high 32-bit words |
| 488 | uvec2 | Accumulation buffer device address |
| 496 | uvec2 | Resolved buffer device address |
| 504 | uint | Entry capacity |
| 508 | uint | Frame index |
| 512 | uint | Shader cache access enabled (diagnostic gate) |
| 516 | uint | Reserved |
| 520 | uint | Reserved |
| 524 | uint | Reserved |
| 528 | float | World-space scene scale |
| 532 | float | Atomic radiance quantization scale |
| 536 | float | Hash-grid logarithm base, currently 2 |
| 540 | float | Hash-grid level bias |

`vulkanite.sharc.sceneScale` and `vulkanite.sharc.radianceScale` override the
defaults 1 and 1000. Resolve defaults to a 64-frame accumulation window and a
128-frame stale-entry lifetime, configurable with
`vulkanite.sharc.accumulatedFrames` and `vulkanite.sharc.staleFrames`.

## Frame order and synchronization

Foundation combines sparse Update work and normal Query work in one RT dispatch.
Queries read the previous resolved buffer while sparse paths atomically write only
the accumulation and hash buffers. Vulkanite then inserts RT-to-compute barriers,
dispatches one Resolve thread per entry, and inserts compute-to-RT barriers for the
next frame. The existing one-frame-in-flight boundary owns retirement.

Foundation updates roughly one path per `SHARC_UPDATE_PERIOD` pixels each frame,
with the subset rotated by frame index. It queries only non-primary diffuse hits
whose incoming segment is at least one cache voxel long. Delta dielectric paths
continue tracing and do not directly query a diffuse radiance entry.

## Validation limits

`tools/ValidateSharc.java` compiles the expanded official Resolve shader and checks
the Foundation declaration and Update/Query call paths. `ValidateFoundation.java`
compiles all pack ray stages. A successful client run must additionally show the
`Vulkanite/SHaRC` initialization log and sustained RT, Resolve, and NGX execution.
Visual comparison is still required for light leaks, temporal lag and cache scale.
