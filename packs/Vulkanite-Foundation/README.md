# Vulkanite Foundation

Foundation is a compact path-tracing reference pack for Vulkanite, Iris, and Sodium on Minecraft 26.3. Install Vulkanite and select Foundation in Iris. Package the `shaders` directory at the ZIP root; include this README and `NOTICE.md` when distributing the pack.

The ray pass uses block and dynamic-scene albedo textures, vertex normals, a small emissive-block map, direct sun and moon lighting, and diffuse multi-bounce transport. Sample count and bounce count are the only path-quality settings. The Iris final stages provide automatic exposure and display tone mapping.

Dynamic entity vertices use the neutral 112-byte triangle-list layout (28 words). Words 0–8 retain position, color, UV0, packed normal, texture slot, and flags; word 9 stores the selected material as a full int32. Words 10–11 and 12–13 hold signed UV1/UV2, 14–15 hold floating-point mid-texture coordinates, 16 stores `at_tangent` as RGBA8_SNORM, 17–20 preserve the four raw `iris_Entity` values in 32-bit words, 21–22 hold signed `mc_Entity`, 23 stores `at_midBlock` as RGBA8_SNORM, 24 contains presence flags, and 25–27 are reserved.

`lib/geometry.glsl` exposes helpers for those additional fields. Word 6 contains a world-space normal; word 16 contains a world-space tangent, orthogonalized to that normal. The host uses the producer's pass transform (or observed hand projection) and flips tangent handedness for mirrored transforms. This reference path uses the normal for diffuse shading and does not consume the tangent or restore PBR shading.

Terrain remains 64 bytes per canonical vertex. When Sodium submits exactly coincident, equally oriented quads in the same corner order, words 10/11 locate complete ordered source quad layers appended to the same geometry buffer. The reference tracer composites those layers by source alpha and vertex color; it does not classify them by block or tint. Words 13–15 expose source/presence flags, the original packed Iris normal and packed `mc_Entity` when present.

The pack consumes terrain geometry and supported dynamic draws supplied through Vulkanite's neutral scene adapter. It does not add a separate geometry publisher or assume that every raster draw is available to ray tracing.

Foundation requires a matching Vulkanite frame and scene ABI. Its composite compute pass publishes the current Iris frame inputs used by the ray pass. Ordinary non-RT shader packs continue through their normal OpenGL path.

OpenDRT and Khronos tone-mapping notices and licenses remain in the pack; see `NOTICE.md` for attribution.
