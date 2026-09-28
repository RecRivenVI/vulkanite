#version 430
/* RENDERTARGETS: 9 */
layout(location=0) out vec4 unused;
uniform sampler2D gtexture;
in vec2 texCoord;
void main(){
    if(texture(gtexture,texCoord).a<0.5) discard;
    unused=vec4(0.0);
}
