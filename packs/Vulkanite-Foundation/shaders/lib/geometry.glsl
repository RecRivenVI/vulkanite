#ifndef FOUNDATION_GEOMETRY
#define FOUNDATION_GEOMETRY
#include "/lib/material.glsl"
#include "/lib/surface.glsl"
layout(location=0) rayPayloadInEXT Surface hit;
layout(set=1,binding=0,std430) readonly buffer Geometry { uint words[]; } geometry[];
layout(set=0,binding=3) uniform sampler2D terrainTexture;
// Scene ABI v4: 48-byte entities with previous positions; 64-byte layered terrain.
layout(set=0,binding=7) uniform sampler2D entityTexturesV4[256];
hitAttributeEXT vec2 barycentric;
uint word(uint vertex, uint field) {
#if ENTITY
    return geometry[nonuniformEXT(gl_InstanceCustomIndexEXT)].words[vertex * 12u + field];
#else
    return geometry[nonuniformEXT(gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT)].words[vertex * 16u + field];
#endif
}
uvec3 triangleVertices() {
    uint q = uint(gl_PrimitiveID) / 2u * 4u;
    return (gl_PrimitiveID & 1) == 0 ? uvec3(q,q+1u,q+2u) : uvec3(q,q+2u,q+3u);
}
vec2 uv(uint v) {
#if ENTITY
    return vec2(uintBitsToFloat(word(v,4u)),uintBitsToFloat(word(v,5u)));
#else
    uint p=word(v,3u); return vec2(p & 65535u,p >> 16u)/65536.0;
#endif
}
vec4 vertexColor(uint v) {
#if ENTITY
    return unpackUnorm4x8(word(v,3u));
#else
    // With separateAo=true, vertex alpha is baked AO, not material coverage.
    return vec4(unpackUnorm4x8(word(v,2u)).rgb,1.0);
#endif
}
uint surfaceMaterial() {
#if ENTITY
    return word(triangleVertices().x,11u)>>16u;
#else
    return word(triangleVertices().x,8u)&65535u;
#endif
}
vec3 position(uint v) {
#if ENTITY
    return vec3(uintBitsToFloat(word(v,0u)),uintBitsToFloat(word(v,1u)),uintBitsToFloat(word(v,2u)));
#else
    uint a=word(v,0u), b=word(v,1u); return vec3(a&65535u,a>>16u,b&65535u)*(32.0/65536.0)-8.0;
#endif
}
vec4 sampleSurface() {
    uvec3 v=triangleVertices(); vec3 b=vec3(1.0-barycentric.x-barycentric.y,barycentric);
    vec2 st=uv(v.x)*b.x+uv(v.y)*b.y+uv(v.z)*b.z;
#if ENTITY
    vec4 texel=textureLod(entityTexturesV4[nonuniformEXT(word(v.x,7u))],st,0.0);
#else
    vec4 texel=textureLod(terrainTexture,st,0.0);
#endif
    vec4 tint=vertexColor(v.x)*b.x+vertexColor(v.y)*b.y+vertexColor(v.z)*b.z;
    vec4 base=vec4(srgbToLinear(texel.rgb)*srgbToLinear(tint.rgb),texel.a*tint.a);
#if !ENTITY
    if((word(v.x,12u)&1u)!=0u) {
        uvec3 packed=uvec3(word(v.x,10u),word(v.y,10u),word(v.z,10u));
        vec2 overlayUV=vec2(dot(vec3(packed&65535u),b),dot(vec3(packed>>16u),b))/65536.0;
        vec4 overlayTexel=textureLod(terrainTexture,overlayUV,0.0);
        vec3 overlayTint=unpackUnorm4x8(word(v.x,11u)).rgb*b.x+unpackUnorm4x8(word(v.y,11u)).rgb*b.y+unpackUnorm4x8(word(v.z,11u)).rgb*b.z;
        vec4 overlay=vec4(srgbToLinear(overlayTexel.rgb)*srgbToLinear(overlayTint),overlayTexel.a);
        float alpha=overlay.a+base.a*(1.0-overlay.a);
        base=vec4((overlay.rgb*overlay.a+base.rgb*base.a*(1.0-overlay.a))/max(alpha,0.00001),alpha);
    }
#endif
    return base;
}
void setHistory(uvec3 v) {
#if ENTITY
    vec3 b=vec3(1.0-barycentric.x-barycentric.y,barycentric);
    hit.previousPosition=vec3(0.0);
    hit.historyValid=1.0;
    for(int i=0;i<3;i++) {
        hit.previousPosition+=b[i]*vec3(uintBitsToFloat(word(v[i],8u)),uintBitsToFloat(word(v[i],9u)),uintBitsToFloat(word(v[i],10u)));
        hit.historyValid*=float((word(v[i],11u)&1u)!=0u);
    }
#else
    hit.previousPosition=hit.position;
    hit.historyValid=1.0;
#endif
}
#endif
