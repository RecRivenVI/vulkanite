# Dynamic geometry in the PT scene

Scene ABI v4 removes the display-referred hand/particle compatibility layer.
Hands, held items and world particles share BLAS storage, hit shaders, path
integrator, geometric guides and SR/RR as entities and terrain. The corresponding
Iris raster shaders discard their fragments; final composition only tone maps the
reconstructed PT image. Vanilla lightmaps, screen-space fog and enchantment glint
are not used to illuminate or decorate these surfaces.

## Geometry and temporal identity

The local first-person body is captured with CAMERA_HIDDEN: primary camera paths
and primary guide rays skip it; shadow and reflected/refracted paths retain it.
First-person hands/items are camera-only: they receive PT shading but are rejected by world shadow and reflected/refracted rays. A camera path already inside held glass may resolve its exit boundary; this does not expose the view model to world rays. The world player model remains responsible for world shadows/reflections. Hands have separate stable identities for main/off hand and item type. The HUD
projection's angular size and hurt/walk bobbing are converted into world-space
geometry. Raster depth compression is not applied, so nearby real geometry can
intersect hands. Third person, spectator mode, hidden HUD, sleeping and detached
camera gates follow the hand renderer. Iris's opaque/translucent hand filter is
set only for the capture call and restored in a finally block.

Billboard particles are extracted individually, including offscreen particles,
with current interpolated position, camera-facing rotation, UV, tint and alpha.
Their object identity and layer/texture identify temporal correspondence. Geometry
positions become world-space before previous positions are matched. Changed UV
layouts conservatively invalidate correspondence; previous buffers never alias
Minecraft's temporary storage. Pickup and elder-guardian model particle states
also submit through the dynamic capture bridge; newly extracted state objects
conservatively invalidate their motion history.

Byte 44 of each 48-byte dynamic vertex is now a flags/material word:

| Bits | Meaning |
| --- | --- |
| 0 | Valid previous position |
| 1 | Hidden from primary camera/guide rays |
| 2 | First-person view model |
| 3 | Fractional stochastic coverage |
| 4 | Particle geometry |
| 16-31 | Foundation material ID (0 = diffuse) |

The descriptor declaration `entityTexturesV4` rejects older packs whose shaders
interpret the flags word differently. Host and pack must be upgraded together.

## Transport

Ordinary textures become diffuse albedo with exact sRGB decoding. Transparent
particle alpha is thin stochastic coverage on all ray categories, including
shadows: a ray continues with probability 1-alpha. Opaque/cutout sprites retain
alpha testing. Fractional hits lower RR history confidence. Entity BLAS geometry
uses NO_DUPLICATE_ANY_HIT_INVOCATION so coverage is not sampled twice for one
primitive in a traversal. These are surface-based particle sprites, **not a
participating-volume smoke solver**; scattering phase functions and density fields
are not provided by Minecraft's billboard data.

Clear held glass/glass panes use the same dielectric material as terrain. Warm
held emitters (glowstone, shroomlight, jack-o-lantern, torch, lantern), cold emitters
(sea lantern, soul torch/lantern) and redstone torches use the existing pack material
IDs 120/121/122. Classification applies only to item/block atlas geometry, not skin.
Flame/small-flame/lava/firefly/glow particles use material 120; soul particles use
121. Other particles are diffuse. These explicit authored material assignments
are relative radiance, not lightmap values. Emission applies to the classified
textured surface as a whole; localized emissive masks need further material data.

World particles participate in reflections, refraction, shadowing and indirect paths. Camera-only hands do not affect world transport.
Emitter lighting currently relies on BSDF sampling (as existing block emission
does), so small emissive particles/held torches can be noisy and are not guaranteed
to converge at low samples. Vanilla non-particle effects such as weather and beacon
beams are separate systems and are not introduced by this change.

## Verification boundary

ValidateDynamicGeometry checks HUD/world projection equivalence under changing
FOV/aspect/bobbing, uncompressed depth, transformed motion history and world reset.
ProbeVisibilityRaster executes the real shaders on the GPU: raster layers remain
empty, and the actual coverage function passes endpoint, alpha probability and
multilayer transmittance checks (four-sigma binomial tolerance).
Validate-Foundation compiles every ray stage/diagnostic mode and runs these checks.

Runtime logs report separately captured body, hand and particle quad counts.
Visual acceptance still requires in-game empty/main/offhand items, attack/equip
animation, glass, water reflections, smoke/splash/debris, camera rotation, FOV
changes, reload/resize, and comparison with raw PT. Night exposure remains frozen
and unresolved from the earlier round.
