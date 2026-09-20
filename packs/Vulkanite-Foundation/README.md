# Vulkanite Foundation

An original diagnostic shader pack for Vulkanite Scene ABI v4 on Minecraft 26.3.
See `docs/foundation-v1.md` at the repository root for the interface and acceptance checklist.

Path tracing defaults to host-owned DLSS Ray Reconstruction at Quality. The shader
options expose Off / Super Resolution / Ray Reconstruction, quality, sample count
and bounce count, automatic exposure and EV compensation. Install the optional native backend described in
`docs/reconstruction-v1.md`; without it, full-resolution raw path tracing remains
available. Diagnostic views bypass DLSS. RR denoises the diffuse path tracer;
SR alone retains its sampling noise. GUI composition remains outside DLSS.

Indirect diffuse paths can use Vulkanite's host-owned SHaRC 1.8.3 service. The
pack supplies sparse cache updates and queries; Vulkanite owns GPU memory, Resolve,
barriers and lifetime. See `docs/sharc-v1.md` in the Vulkanite repository.

Select **Diagnostic view** in Iris shader pack settings:

- 0: path tracing (4 samples/pixel, up to 8 surface events), physical water/clear glass,
  solar/lunar/stellar illumination, atmospheric single scattering and automatic exposure
- 1: albedo
- 2: geometric normals
- 3: material identity (blue water, cyan clear glass, orange emitters)
- 4: entity mask (orange entities, green terrain)

Package the `shaders` directory at the ZIP root. No DemoPack files are required.
This is a correctness/acceptance pack, not a finished visual-effects pack.
See `docs/foundation-transport.md` for the models, validation and remaining limits.
Current pack requires Reconstruction ABI v2; update the mod with the pack.

First-person hands/held items receive PT shading and geometric SR/RR guides but
are visible only to the player camera, without world shadows/reflections. World
particles fully participate in scene transport. The raster compatibility layer is disabled. See
`docs/foundation-visibility.md` for coverage, material assignments and limitations;
billboard smoke is thin stochastic geometry, not a volumetric simulation.
