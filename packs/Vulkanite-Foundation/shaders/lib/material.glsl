#ifndef FOUNDATION_MATERIAL
#define FOUNDATION_MATERIAL
const float PI=3.14159265359;
const uint WATER=100u, GLASS=101u;
vec3 srgbToLinear(vec3 c) {
    return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),greaterThan(c,vec3(0.04045)));
}
bool dielectric(uint material) { return material==WATER || material==GLASS; }
float ior(uint material) { return material==WATER?1.333:material==GLASS?1.5:1.0; }
vec3 absorption(uint material) {
    // Homogeneous clear water, metres^-1 with one block = one metre. No biome tint painted on the interface.
    return material==WATER?vec3(0.35,0.065,0.025):vec3(0.0);
}
float fresnelDielectric(float cosine,float etaI,float etaT) {
    float sinT2=(etaI*etaI)/(etaT*etaT)*max(0.0,1.0-cosine*cosine);
    if(sinT2>=1.0) return 1.0;
    float cosT=sqrt(1.0-sinT2);
    float rs=(etaI*cosine-etaT*cosT)/(etaI*cosine+etaT*cosT);
    float rp=(etaT*cosine-etaI*cosT)/(etaT*cosine+etaI*cosT);
    return 0.5*(rs*rs+rp*rp);
}
vec3 emission(uint material,vec3 albedo) {
    // Explicit relative radiance values. Vanilla block-light levels are not radiometric units.
    if(material==110u) return vec3(8.0,2.0,0.2)*albedo;
    if(material==120u) return vec3(6.0,3.6,1.5)*albedo;
    if(material==121u) return vec3(1.5,4.0,6.0)*albedo;
    if(material==122u) return vec3(3.0,0.05,0.02)*albedo;
    return vec3(0.0);
}
#endif
