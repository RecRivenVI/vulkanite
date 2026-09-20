# Vulkanite Foundation — Scene ABI v4

Target: Minecraft 26.3, Fabric, Sodium 0.9.2, Iris 1.11.6. This is an alpha interface,
not a compatibility promise for existing packs. The rendering stack remains OpenGL/Vulkan interop.

Foundation is the first consumer of Vulkanite's host-owned
[reconstruction service](reconstruction-v1.md) and [SHaRC service](sharc-v1.md).
The services are infrastructure; this pack supplies their Vulkan PT inputs.

## Scene and shader contract

The common descriptor set contains camera UBO binding 0, TLAS binding 1, terrain
albedo binding 3, optional terrain normal/specular bindings 4/5, output images[16]
binding 6, and entity albedo samplers[256] named `entityTexturesV4` at binding 7. That name declares Scene ABI v4; declaring binding 7 opts into
dynamic entity ABI v3. Every ray pass must opt in consistently and provide exactly two hit
groups: terrain at SBT record 0 and entities at record 1. Unsupported layouts fail
at pipeline construction. Ray calls use SBT offset/stride 0; instance offsets select groups.

Geometry is a runtime array of storage buffers at set 1, binding 0. Slot 0 contains
one combined entity vertex buffer (one BLAS geometry), indexed by primitive ID.
Terrain instances select their geometry buffers using instanceCustomIndex + geometryIndex.
Quads use triangles (0,1,2), (0,2,3). Entity vertices are 48 bytes:

| Byte offset | Data |
| --- | --- |
| 0 | World position, float32 x3 |
| 12 | Vertex color, RGBA8 UNORM |
| 16 | UV, float32 x2 |
| 24 | Normal, SNORM8 x4 (zero when unavailable) |
| 28 | Entity texture index, uint32 |
| 32 | Previous rendered world position, float32 x3 |
| 44 | Flags/material: bit 0 history valid, 1 camera-hidden, 2 view model, 3 fractional coverage, 4 particle; bits 16-31 material ID |

Entity texture indices are frame-local. More than 256 distinct textures fails with
an explicit error, rather than truncating the scene. Standard textured QUADS from
the feature renderers are captured. Position/UV formats are checked; color/normal
have defaults. Spectator and invisible entities are excluded. The first-person
camera entity is captured with flag bit 1: Foundation any-hit ignores it for the
primary guide and first path segment, but retains it for shadows and secondary
reflections. History validity must test bit 0 rather than the entire word.
Entity positions are interpolated world positions from render states.
All entity vertices are copied before Minecraft's temporary mesh buffers are reused.
Previous positions are matched by entity UUID, render type, texture and vertex UV
sequence, independently of global entity ordering. New/disappearing entities,
world changes and changed UV topology invalidate correspondence. Procedural models
which reorder indistinguishable UV vertices are not yet generally identifiable.

Terrain uses a 64-byte layered layout produced by
TerrainVertexCompatibility: the first 40 bytes retain position/color/UV/normal data; offset 40 stores overlay UV (UNORM16 x2), offset 44 overlay RGBA8 tint, offset 48 layer flags (bit 0 = present), and bytes 52–63 are reserved. Foundation reads it explicitly as words. There is no
raw Sodium vertex-format dependency in the pack.

## Ownership and synchronization

- One ray frame is in flight. At the next frame boundary, GL finishes its prior
  reads and Vulkan queue 0 completes before entity buffers/descriptors are replaced.
  This deliberately trades throughput for an auditable first-round lifetime model.
- Entity BLAS and geometry survive the TLAS build fence and remain alive through
  ray dispatch. They retire at the next safe frame boundary or world cleanup.
- Shared output, terrain, custom and entity textures participate in GL/Vulkan
  semaphore handoff. Vulkan layouts transition for sampling and return to GENERAL.
- Quad indices belong to a build command, including uploads. There is no mutable
  global index allocation shared unsafely by the two queues.
- Descriptor pools account for array lengths. Pipelines own their layouts and pools;
  pipeline layouts are destroyed explicitly. Image views are retired before images.
- World cleanup drains the BLAS worker with a bounded wait, waits for both GPU
  queues, processes fences, and frees unpublished results. It no longer guesses
  completion with a fixed sleep. Disposed/stale section builds are rejected; an
  empty section build removes old geometry. Empty TLAS builds remain valid.

## Foundation acceptance pack

Source: `packs/Vulkanite-Foundation`. Newly authored diagnostic shaders, independent
of DemoPack. Modes: multi-bounce diffuse path tracing with sun next-event estimation and sky lighting, albedo, geometric
normals, material identity and entity mask. Physical transport now includes water/clear-glass
reflection/refraction, atmosphere, solar/lunar/stellar lighting and automatic exposure;
see [transport models and limits](foundation-transport.md). Path-tracing mode
opts into host-owned DLSS SR/RR through [Reconstruction ABI v2](reconstruction-v1.md).
Diagnostic views disable reconstruction.

Manual acceptance:

1. Check a cow/sheep, player in third person, and a dropped item: position, texture,
   animation, and occlusion behind a solid block.
2. In mode 4, entities should be orange and terrain green; mode 1 checks UV/color.
3. In mode 0, an entity should cast a ray-traced shadow and appear in water reflections.
4. Add/remove blocks including the last block in a section; check for ghost geometry.
5. Reload shaders repeatedly, resize the window, exit/re-enter the world, and quit.
   Inspect logs for Vulkan errors, duplicate releases, worker timeouts or crash reports.

First-person hands and particles now enter PT through the dynamic geometry bridge;
see `foundation-visibility.md` for capture, material, coverage and verification details.
Not covered by first-round acceptance: block-entity capture, text/glint,
general translucent entity materials, entity PBR normal maps, huge-coordinate
precision, and performance optimization. Passing compilation/log checks does not
prove visual acceptance or long-duration memory stability.

## Coplanar biome-tint overlays

TerrainLayers merges exactly two coincident, same-facing quads when one has an achromatic base color and the other has chromatic tint, with distinct UVs. Vertex correspondence is recovered from positions, including across render passes. Both layers become one acceleration primitive; the shader composites the tinted overlay over the untinted base using texture alpha. This targets the vanilla grass-side overlay pattern without position bias. Ambiguous multi-layer or entirely achromatic overlays are not yet generalized. Engine and pack must be updated together.
