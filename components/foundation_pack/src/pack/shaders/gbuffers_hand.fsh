#version 430
/* RENDERTARGETS: 9 */
layout(location=0) out vec4 unused;
uniform sampler2D gtexture;
in vec2 texCoord;
// Hand/item cutout holes must not write depth (same 0.5 threshold as PT rahit).
// Covered pixels stay at the nearest depth so depth-composited content cannot
// cover the first-person arm.
void main() {
    if (texture(gtexture, texCoord).a < 0.5) discard;
    gl_FragDepth = 0.0;
    unused = vec4(0.0);
}
