#version 430
/* RENDERTARGETS: 9 */
layout(location=0) out vec4 unused;
flat in float materialIdentity;
void main(){unused=vec4(materialIdentity,0.0,0.0,1.0);}
