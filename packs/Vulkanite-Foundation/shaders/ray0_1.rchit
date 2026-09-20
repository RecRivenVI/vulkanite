#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require
#define ENTITY 1
#include "/lib/geometry.glsl"
void main() {
    uvec3 v=triangleVertices();
    vec3 n=normalize(transpose(mat3(gl_WorldToObjectEXT))
            *cross(position(v.y)-position(v.x),position(v.z)-position(v.x)));
    if(dot(n,gl_WorldRayDirectionEXT)>0.0) n=-n;
    hit.position=gl_WorldRayOriginEXT+gl_HitTEXT*gl_WorldRayDirectionEXT;
    hit.distance=gl_HitTEXT;
    setSurfaceMaterial(v,n);
}
