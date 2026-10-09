#version 430
#include "/lib/tonemap.glsl"
#include "/lib/exposure.glsl"
#include "/lib/opendrt.glsl"
uniform sampler2D colortex0;
uniform sampler2D colortex8;
#define TONE_MAPPING 2 // [0 1 2]
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
    float metered=AUTO_EXPOSURE==1?appliedExposureEV(exposure):0.0;
    float applied=validExposure(exposure) && !isnan(exposure.b) && !isinf(exposure.b)
        ?clamp(exposure.b,EXPOSURE_ABI_MIN_EV,EXPOSURE_ABI_MAX_EV):0.0;
    vec3 c=texture(colortex0,texCoord).rgb;
    if(any(isnan(c)) || any(isinf(c))) c=vec3(0.0);
    c=max(c,vec3(0.0))*exp2(metered-applied+float(EXPOSURE_EV));
    // All three transforms return linear display RGB; encode exactly once.
    c=TONE_MAPPING==2?openDrtDisplay(c):(TONE_MAPPING==0?neutralDisplay(c):acesDisplay(c));
    fragColor=vec4(linearToSrgb(clamp(c,0.0,1.0)),1.0);
}
