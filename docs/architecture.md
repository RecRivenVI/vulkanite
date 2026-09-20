# Architecture contract

Vulkanite is a Vulkan execution backend for Iris shader packs. Minecraft, Sodium,
and Iris remain the source of world rendering data and retain ownership of the
OpenGL window and GUI. This contract defines the rewrite's boundaries; supported
bindings and execution stages are documented in `shader-pack-interface.md`.

## Responsibilities

- Upstream renderers produce geometry, textures, draw state, and frame inputs.
- Version adapters translate supported public rendering data into owned scene
  inputs. Vulkanite manages resource lifetimes, acceleration structures, shader
  execution, and OpenGL/Vulkan synchronization.
- Packs define materials, light transport, exposure, display transforms, and the
  Vulkan work they request. The reference pack uses the same interface as other
  packs.

There are no gameplay blocks, external scene publishers, mod-name dispatch rules,
or material-specific geometry repairs. Necessary coordinate, topology, texture,
and first-person pass metadata are rendering inputs, not optical policies.

## Data and execution

Capture upstream data while it is valid. A composite execution boundary consumes
the completed inputs for that frame; it cannot recover expired temporary buffers
or geometry that upstream never submitted. Do not re-run world simulation or
enumerate another mod's private objects to recreate its draws.

Packs declare where Vulkan work runs relative to Iris composite work, and the
logical resources it reads and writes. GL producers finish before Vulkan consumes
their results; Vulkan writes finish before later GL consumers run. Binding a
logical color target must respect Iris's current buffer version. Iris final
continues to produce the displayed world image, followed by ordinary game UI.

World parameters come from Iris. A pack can transfer its required Iris uniforms,
including custom values, through declared shared buffers. Host-side camera data
exists only for scene coordinates and execution requirements; it must not become
a second independent implementation of game lighting or environment state.

Normal OpenGL packs do not opt into Vulkan resources or execution. Source
compatibility with old Vulkanite pack interfaces is not a requirement.

## Geometry compatibility

Compatibility follows supported drawing contracts rather than lists of mods.
Vertex/index ranges, topology, instance state, transforms, and texture bindings
must describe the actual submitted geometry. Buffer addresses are not durable
object identities. Async work must own a copy or an explicit valid resource lease.

Custom vertex deformation, geometry generated exclusively on the GPU, and
material semantics absent from the input are explicit capability boundaries.
Camera visibility alone does not prove participation in PT shadows, reflections,
or indirect transport. Unsupported input must not be silently reported as fully
compatible.

PhysicsMod is a compatibility test case for common drawing paths, not a reason
to add a PhysicsMod-specific adapter or infer its private material meaning.

## Current scope

The rewrite targets raw path tracing with a small reference pack. DLSS/NGX,
super-resolution, denoising, temporal reconstruction, radiance caching/SHaRC,
frame generation, and specialized glass/SSS experiments are outside this scope.
Future reconstruction services must be designed from their actual input contracts
rather than retained vendor-specific assumptions.

Correct synchronization, bounds checks, failure cleanup, and ordinary shader and
pipeline caches remain infrastructure responsibilities. Development profiling,
runtime probes, diagnostic views, and periodic telemetry are not shipped features.

## Acceptance

Verify normal OpenGL pack isolation, ordered GL/Vulkan/GL resource exchange,
terrain updates, indexed dynamic geometry, multiple particle layers, texture
reloads, dimension changes, resizing, and resource retirement. Verify generic mod
geometry using its normal render path and report any missing semantics explicitly.

Automated client work uses the repository run directory and disposable test data.
Prism receives validated artifacts for the user's manual acceptance; it is not an
automation workspace. Build, bounded runtime evidence, and visual acceptance are
reported separately. Temporary fixtures and logs are recycled after verification.
