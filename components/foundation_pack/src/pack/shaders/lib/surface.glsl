#ifndef FOUNDATION_SURFACE
#define FOUNDATION_SURFACE
struct Surface {
    vec3 position;
    float distance;
    vec3 normal;
    float rayConeWidth;
    vec3 geometricNormal;
    float rayConeSpread;
    vec4 color;
    uint material;
    uint visibilityFlags;
    uint coverageSeed;
};
#endif
