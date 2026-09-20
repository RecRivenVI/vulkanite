# Presentation foundation: GUI composition and Windows WSI probe

This is the first DLSS preparation stage, not a DLSS implementation. Java/LWJGL,
Vulkan RT and Minecraft/Iris OpenGL remain the production rendering path.

## GUI boundary

Minecraft 26.3 extracts GUI state before submitting it. `MixinGuiRenderer` wraps
the actual `draw` and `executeDrawRange` calls after PIP/item-atlas preparation.
It preserves the vanilla range order, including the boundary around menu blur.

Within each range, contiguous standard straight-alpha or premultiplied-alpha
draws render into a display-resolution transparent RGBA8 target, with an independent
copy of the current depth attachment. Compute composition uses
`ui + (1 - ui.a) * background`; depth is copied back before the next segment.
Clear explicitly uses `(0,0,0,0)`; JOML's default `Vector4f()` has w=1.

Other blend equations, missing color channels and unknown attachment formats
remain direct draws in their original positions. In particular, the inverted
crosshair is not changed to a conventional transparent crosshair. Menu blur still
executes on the already composited image. Both cases are recorded as background
dependencies, so a future FG adapter cannot silently call the frame one ordinary
RGBA UI layer. PIP/item previews are composited where vanilla draws their textures;
they do not use the world camera or join the RT scene.

`FrameStatus.singleLayerRepresentable` describes only the 2D GUI algebra. It is
NOT `fgReady`: the pre-GUI snapshot still includes any upstream 3D HUD/screen
effects, and current segment images have no multi-frame lifetime/export contract.
Empty UI frames also must not export a stale segment texture. No SDK consumes these
images yet. A later presentation interface must retain a complete composition
graph or explicitly decline FG for background-dependent effects.

## Verification and switches

- Default: composition enabled, GPU readback verification disabled.
- `-Dvulkanite.ui.disabled=true`: retain the original complete GUI path.
- `-Dvulkanite.ui.verifyInterval=60`: every 60 GUI frames, also execute the same
  draw range into a reference target and compare RGB8 against recomposition.
  This is deliberately expensive and is for diagnosis only.
- `-Dvulkanite.ui.dumpDirectory=<absolute path>`: export the first mismatching
  actual/reference/UI/pre-GUI textures. No desktop capture or input automation.

The reference check compares each draw range, not an independently rendered
second world or the entire blur chain. Up to 2/255 per RGB channel is tolerated
for intermediate UNORM rounding. Larger errors restore that range's reference
and disable composition for the rest of the client session. Alpha in the final
opaque frame is not the UI coverage metric.

Run `tools/Validate-Presentation.ps1 -JavaHome <Java25>` after building and generating
the Loom runClient classpath. `ProbeUiComposition.java` uses real GL blending as
the reference for two overlapping translucent rectangles, tests zero background
alpha at two sizes, and verifies compute program/image bindings are restored.
`ProbeVulkanPresentation.java` is a separate process with hidden SDL windows;
it creates no Minecraft state and never replaces the game window.

## Evidence from 2026-09-18

- GPU UI fixture: 64x48 and 137x79, maximum RGBA8 error 1/255; transparent alpha
  and state-restoration checks passed.
- Real 26.3 Foundation client: reference comparisons at 854x480 and 3840x2054
  reported maximum RGB error 0–1/255 and zero pixels beyond 2/255 in all 54 observed samples.
- User accepted crosshair, HUD, chat, inventory previews, tooltips and menu blur
  in the diagnostic client. This does not cover arbitrary third-party GUI mods.
- Windows 11 / RTX 4080 SUPER, NVIDIA 616.92: attempting a Vulkan swapchain on
  the SDL window already used by GL returned `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`.
- Keeping the GL producer/context and using a separate native window succeeded:
  12 acquire/clear/submit/present operations across 128x96 → 192x128 recreation.
  GL rendering and swap still worked after Vulkan teardown.

The same-window rejection matches the
[Vulkan swapchain specification](https://docs.vulkan.org/refpages/latest/refpages/source/vkCreateSwapchainKHR.html):
a native window already associated with a non-Vulkan graphics surface cannot
simultaneously own the Vulkan swapchain. This is not a missing shader or DLSS DLL.

## Next boundary

For FG, investigate a visible Vulkan presentation window plus a hidden GL producer
drawable while preserving SDL input, DPI, focus, fullscreen and Minecraft window
lifecycle. The probe only proves hidden-window WSI submission and resize; it does
not prove visible presentation, GL/Vulkan texture handoff, pacing, Streamline hooks,
Reflex, DLSS or frame generation. The current game continues to present through GL.
Do not replace its window until those additional boundaries have independent evidence.
