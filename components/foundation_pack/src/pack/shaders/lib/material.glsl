#ifndef FOUNDATION_MATERIAL
#define FOUNDATION_MATERIAL
const float PI=3.14159265359;
const uint EMISSIVE_LAVA=110u;
const uint EMISSIVE_WARM=120u;
const uint EMISSIVE_COOL=121u;
const uint EMISSIVE_RED=122u;

vec3 srgbToLinear(vec3 c) {
    return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),greaterThan(c,vec3(0.04045)));
}

vec3 emission(uint material,vec3 albedo) {
    if(material==EMISSIVE_LAVA) return vec3(8.0,2.0,0.2)*albedo;
    if(material==EMISSIVE_WARM) return vec3(6.0,3.6,1.5)*albedo;
    if(material==EMISSIVE_COOL) return vec3(1.5,4.0,6.0)*albedo;
    if(material==EMISSIVE_RED) return vec3(3.0,0.05,0.02)*albedo;
    return vec3(0.0);
}
#endif
