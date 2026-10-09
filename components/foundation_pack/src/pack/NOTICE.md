# Shader pack licensing

The Foundation shader pack includes OpenDRT v1.1.0 by Jed Smith under GPLv3.
The combined shader pack is distributed under GPL-3.0-only. This does not
relicense the separately distributed Vulkanite Java/native mod.

The OpenDRT GLSL specialization is derived from:
<https://github.com/jedypod/open-display-transform/tree/af683323e2a8a63501f02c0a724ec538e3228ad0>

Changes: Standard look fixed to SDR 100-nit Rec.709/D65, bright surround;
linear Rec.709 input and linear display RGB output; unused presets and
branches removed; DCTL intrinsics translated to GLSL. The final shader applies
piecewise sRGB encoding separately. Full license: `shaders/lib/licenses/OpenDRT-GPL-3.0.txt`.

The PBR Neutral alternative retains Khronos Group's Apache-2.0 notices and
license in `shaders/lib/licenses/Khronos-ToneMapping.txt`.
