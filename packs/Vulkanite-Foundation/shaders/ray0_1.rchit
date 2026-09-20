#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require
#define ENTITY 1
#include "/lib/geometry.glsl"
void main() {
    uvec3 v=triangleVertices();
    vec3 n=normalize(cross(position(v.y)-position(v.x),position(v.z)-position(v.x)));
    n=normalize(transpose(mat3(gl_WorldToObjectEXT))*n);
    if(dot(n,gl_WorldRayDirectionEXT)>0.0) n=-n;
    hit.position=gl_WorldRayOriginEXT+gl_HitTEXT*gl_WorldRayDirectionEXT;
    hit.distance=gl_HitTEXT; hit.normal=n; hit.kind=uint(ENTITY)|(word(v.x,11u)&4u);
    hit.material=surfaceMaterial(); hit.color=sampleSurface();
    if(dielectric(hit.material)) hit.color=vec4(1.0);
    setHistory(v);
    // Fractional coverage has multiple depth layers; reduce reuse of a single layer's guide.
    if((word(v.x,11u)&8u)!=0u) hit.historyValid*=hit.color.a;
}
