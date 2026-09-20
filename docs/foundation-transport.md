# Foundation light transport

The pack uses a scene-linear RGB path tracer, with one block interpreted as one
metre. Physical transport models replace the old fixed ambient fill, painted
directional shading and global 65% mirror blend. This is a bounded numerical
model, not a claim of exact spectral rendering or complete Minecraft coverage.

## Material inputs

- `oldLighting=false`, `separateAo=true`, and `ambientOcclusionLevel=0` remove
  vanilla face shading/AO from the diffuse reflectance used by transport.
- Terrain vertex alpha is the separate AO channel, never texture coverage.
  Base and grass-overlay coverage come from texture alpha. Biome tint remains
  material reflectance; it is not illumination.
- Texture and tint RGB use the piecewise sRGB transfer function before multiplication
  and overlay composition. Vanilla lightmap values never illuminate PT surfaces.
- `block.properties` provides explicit material identities. `mc_Entity` remains an
  active terrain/water raster attribute so Iris actually encodes those IDs; the host
  already carries the ID at byte 32 of its 64-byte terrain vertex format.
- Unspecified opaque surfaces are ideal Lambert reflectors. Alpha-tested foliage
  retains texture coverage. Water and clear glass ignore the decorative texture's
  alpha/color and become dielectric interfaces, rather than opaque cutouts.
- Selected luminous blocks are area emitters with explicitly assigned relative
  radiance. Their vanilla block-light number is not treated as emitted radiance.
  Emission is sampled by BSDF paths, so small emitters can converge slowly.

## Water and clear glass

The dielectric BSDF uses exact unpolarized Fresnel, Snell refraction and total
internal reflection. Reflection/transmission probabilities are Fresnel weights;
the radiance throughput includes the transmission eta-squared factor. Russian
roulette accounts for eta scaling so temporary refraction attenuation does not
prematurely terminate paths. A small medium stack tracks transmission events;
reflection leaves the medium unchanged. The camera water flag seeds the initial
medium for underwater views.

Water IOR is 1.333, clear glass 1.5. Water RGB absorption coefficients are
`(0.35, 0.065, 0.025) m^-1`, an explicit homogeneous clear-water model, applied as
`exp(-sigma_a * distance)` along every segment in water. They are illustrative
RGB coefficients, not a spectral measurement of every biome's water. Clear glass
is nonabsorbing. Water is calm geometry; there are no fake scrolling water colors
or animated normals disconnected from the intersection surface.

## Illumination and display

The Sun and Moon are finite disks of angular radius 0.00465 radians. Diffuse
next-event estimation selects them using attenuated irradiance and samples their
solid angle; power-heuristic MIS balances that estimator against BSDF paths hitting
the disks. Full-moon irradiance is 2.5e-6 times solar irradiance and follows a Lambert
phase curve from Minecraft's moon phase. Specular chains see these emitters and sky.
Shadow rays stop at refractive boundaries: the tracer does not invent straight-line
alpha shadows through water. Refractive caustics remain valid BSDF paths, but are
high variance without a dedicated caustic sampler.

The old horizon gradient is replaced by spherical atmospheric single scattering:
Rayleigh and anisotropic Mie phase functions, altitude-dependent densities,
solar/lunar/view transmittance and planet occlusion. The integration uses 8 view and 4
light steps. An attenuated unresolved stellar background supplies incoming night
radiance through actual visibility paths; it does not light sealed caves. It is a
coarse clear-atmosphere approximation; multiple scattering,
ozone, volumetric clouds and weather are not implemented. The game supplies solar
and lunar direction. The lunar disk uses phase-integrated brightness, without
resolving the terminator on the disk. Stars are integrated background illumination,
not individually resolved points. There is no arbitrary minimum surface light.

The default path budget is 8 surface events, configurable to 4/6/8/12. Finite path
length, finite scene extent, RGB spectra and geometric precision remain approximations.
Reconstruction ABI v2 uses RGBA32F for color input/output, retaining faint night
radiance instead of quantizing it to zero in binary16. There is no firefly clamp.
Automatic exposure meters log-average world luminance using a 32x32 sample grid
over the central 20% of the image area in
`final.csh`, retains a 1x1 RGBA32F history, and adapts in exposure stops (faster toward
bright light, slower toward darkness). It has finite -8..22 EV camera limits; truly
zero radiance stays black. Metering excludes GUI. User EV compensation and
PBR Neutral tone mapping happen after DLSS, followed by piecewise sRGB encoding.
Exposure is a display control, not a change to light transport.

The previous-frame camera gain pre-exposes radiance before SR/RR, and the host sends
that exact gain to NGX. Metering and display remove the applied gain before using
the new exposure value. This keeps very weak night inputs useful to reconstruction
without altering physical light ratios or accidentally applying exposure twice.

The visual reference is Radiance/MCVR's default PBR Neutral tone mapper and central
metering. Foundation uses the published Khronos operator with the reference's 0.01
highlight desaturation parameter. It does not import MCVR's indirect-light gain
(8/16), basic-radiance floor, or greatly amplified blue lunar emitter: those would
change the physical transport model. Sources were inspected read-only; no adjacent
project files were modified. The Khronos implementation attribution and Apache-2.0
license are included with the pack.

## Reconstruction and diagnostics

SR/RR inputs remain linear and carry explicit pre-exposure. Opaque materials supply diffuse albedo;
water/glass supply zero diffuse, Fresnel specular albedo, zero roughness and the
actual reflected ray's first-hit distance. Primary depth/motion describe the visible
interface. A single depth/motion layer cannot perfectly describe all objects seen
through refraction; moving refracted content remains a temporal acceptance case.

Views: 0 PT, 1 albedo, 2 normal, 3 material identity (blue water, cyan clear glass,
orange emitters, gray diffuse), 4 entity identity. Diagnostic views disable DLSS.

## Validation and limits

`Validate-Foundation.ps1` compiles all RT stages and views and tests terrain material
ID/AO preservation. `ProbePhysicalTransport.java` runs the actual GLSL functions
on a hidden GPU fixture and checks water's normal-incidence Fresnel (~2%), grazing
reflection, TIR, Snell refraction, parallel-slab exit direction, Beer attenuation,
day/night solar visibility, sRGB conversion, low-light metering, black preservation
and gradual exposure adaptation. This is not a substitute for game
water geometry and visual acceptance.

Manual checks: view a shore at normal/grazing angles; look at the displaced bottom;
cross the water surface; check underwater total internal reflection; compare shallow
and deep water; inspect water and glass in material view; check a dark cave, a
sunlit surface and an emissive block. Test with RR and raw output to distinguish
transport from reconstruction artifacts.

General participating water scattering, specialized caustic sampling, colored-glass
absorption, camera initialization inside arbitrary glass volumes, arbitrary
overlapping/waterlogged volume topology, block entities and unsupported scene
effects remain outside this model. Stock albedo textures may contain artist-painted
lighting that cannot be reliably removed without replacement material assets.

References: [PBRT dielectric BSDF](https://www.pbr-book.org/4ed/Reflection_Models/Specular_Reflection_and_Transmission),
[transmittance](https://pbr-book.org/4ed/Volume_Scattering/Transmittance),
[path-tracer MIS](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer).
