#version 430
#include "/lib/tonemap.glsl"
uniform sampler2D colortex0;
uniform sampler2D colortex8;
#define AUTO_EXPOSURE 1 // [0 1]
#define EXPOSURE_EV 0 // [-2 -1 0 1 2 3 4 5 6]
in vec2 texCoord;
layout(location=0) out vec4 fragColor;
/*
const int colortex0Format = RGBA32F;
const int colortex8Format = RGBA32F;
const bool colortex8Clear = false;
*/
vec3 linearToSrgb(vec3 c) {
    return mix(12.92*c,1.055*pow(c,vec3(1.0/2.4))-0.055,greaterThan(c,vec3(0.0031308)));
}
void main(){
    vec4 exposure=texelFetch(colortex8,ivec2(0),0);
    float metered=AUTO_EXPOSURE==1?exposure.r:2.0;
    vec3 c=max(texture(colortex0,texCoord).rgb,vec3(0.0))*exp2(metered-exposure.b+float(EXPOSURE_EV));
    // PBR Neutral display response; transport and reconstruction remain linear.
    c=neutralDisplay(c);
    fragColor=vec4(linearToSrgb(clamp(c,0.0,1.0)),1.0);
}
