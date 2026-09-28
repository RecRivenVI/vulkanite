#version 430
/* RENDERTARGETS: 9 */
layout(location=0) out vec4 unused;
uniform sampler2D gtexture;
in vec2 texCoord;
flat in float materialIdentity;
const float ambientOcclusionLevel=0.0;
// Cutout must discard before depth write so GL depth holes match PT cutout.
// Threshold matches Foundation rahit (0.5).
void main(){
    if(texture(gtexture,texCoord).a<0.5) discard;
    unused=vec4(materialIdentity,0.0,0.0,1.0);
}
