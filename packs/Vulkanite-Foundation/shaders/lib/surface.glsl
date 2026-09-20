#ifndef FOUNDATION_SURFACE
#define FOUNDATION_SURFACE
struct Surface {
    vec3 position; float distance;
    vec3 normal; uint kind;
    vec4 color;
    vec3 previousPosition; float historyValid;
    uint material;
    uint cameraRay;
    uint coverageSeed;
};
#endif
