#ifndef FOUNDATION_GEOMETRY
#define FOUNDATION_GEOMETRY
#include "/lib/material.glsl"
#include "/lib/surface.glsl"
layout(location=0) rayPayloadInEXT Surface hit;
layout(set=1,binding=0,std430) readonly buffer Geometry { uint words[]; } geometry[];
layout(set=0,binding=3) uniform sampler2D terrainTexture;
layout(set=0,binding=7) uniform sampler2D entityTextures[256];
hitAttributeEXT vec2 barycentric;

uint word(uint vertex,uint field) {
#if ENTITY
    return geometry[nonuniformEXT(gl_InstanceCustomIndexEXT)].words[vertex*28u+field];
#else
    return geometry[nonuniformEXT(gl_InstanceCustomIndexEXT+gl_GeometryIndexEXT)].words[vertex*16u+field];
#endif
}

#if ENTITY
// Neutral dynamic-vertex accessors. Packed RG16_SINT channels are sign-extended
// by the producer into one int32 word per component; helpers return those values.
int signedInt32(uint bits) {
    if((bits&0x80000000u)==0u) return int(bits);
    uint magnitude=(~bits)+1u;
    return magnitude==0x80000000u?-2147483647-1:-int(magnitude);
}
ivec2 entityUv1Raw(uint vertex) { return ivec2(signedInt32(word(vertex,10u)),signedInt32(word(vertex,11u))); }
ivec2 entityUv2Raw(uint vertex) { return ivec2(signedInt32(word(vertex,12u)),signedInt32(word(vertex,13u))); }
vec2 entityMidTexCoord(uint vertex) {
    return vec2(uintBitsToFloat(word(vertex,14u)),uintBitsToFloat(word(vertex,15u)));
}
vec4 entityTangent(uint vertex) { return unpackSnorm4x8(word(vertex,16u)); }
ivec4 entityIrisEntityRaw(uint vertex) {
    return ivec4(signedInt32(word(vertex,17u)),signedInt32(word(vertex,18u)),
            signedInt32(word(vertex,19u)),signedInt32(word(vertex,20u)));
}
ivec2 entityMcEntityRaw(uint vertex) { return ivec2(signedInt32(word(vertex,21u)),signedInt32(word(vertex,22u))); }
vec4 entityMidBlockSnorm(uint vertex) { return unpackSnorm4x8(word(vertex,23u)); }
uint entityPresenceFlags(uint vertex) { return word(vertex,24u); }
uvec3 entityReservedWords(uint vertex) {
    return uvec3(word(vertex,25u),word(vertex,26u),word(vertex,27u));
}
#endif

#if !ENTITY
uint terrainPresenceFlags(uint vertex) { return word(vertex,13u); }
uint terrainIrisNormalRaw(uint vertex) { return word(vertex,14u); }
uint terrainMcEntitySourceRaw(uint vertex) { return word(vertex,15u); }
uint terrainLightAndDataRaw(uint vertex) { return word(vertex,4u); }
bool terrainHasCompactMaterialBits(uint vertex) { return (terrainPresenceFlags(vertex)&256u)!=0u; }
uint terrainCompactMaterialBits(uint vertex) { return (terrainLightAndDataRaw(vertex)>>16u)&255u; }
uvec2 terrainLightmap(uint vertex) {
    uint packed=terrainLightAndDataRaw(vertex);
    return uvec2(packed&255u,(packed>>8u)&255u);
}
uint terrainLayerStartWords(uint vertex) { return word(vertex,10u); }
uint terrainLayerCount(uint vertex) { return word(vertex,11u); }
#endif

uvec3 triangleVertices() {
#if ENTITY
    uint first=uint(gl_PrimitiveID)*3u;
    return uvec3(first,first+1u,first+2u);
#else
    uint first=uint(gl_PrimitiveID)/2u*4u;
    return (gl_PrimitiveID&1)==0?uvec3(first,first+1u,first+2u):uvec3(first,first+2u,first+3u);
#endif
}

vec2 uv(uint vertex) {
#if ENTITY
    return vec2(uintBitsToFloat(word(vertex,4u)),uintBitsToFloat(word(vertex,5u)));
#else
    uint packed=word(vertex,3u);
    return vec2(packed&65535u,packed>>16u)/65536.0;
#endif
}

vec4 vertexColor(uint vertex) {
#if ENTITY
    return unpackUnorm4x8(word(vertex,3u));
#else
    // Terrain alpha stores baked AO when separateAo=true.
    return vec4(unpackUnorm4x8(word(vertex,2u)).rgb,1.0);
#endif
}

vec4 sampleOneSurface(uvec3 v,vec3 b);

uint entityFlags(uint vertex) {
#if ENTITY
    return word(vertex,8u);
#else
    return 0u;
#endif
}

bool alphaBlendFlag(uint vertex) {
#if ENTITY
    return (entityFlags(vertex)&32u)!=0u;
#else
    return (word(vertex,12u)&8u)!=0u;
#endif
}

bool alphaDiscardSurface(uint vertex) {
#if ENTITY
    uint flags=entityFlags(vertex);
    return (flags&4u)!=0u&&(flags&32u)==0u;
#else
    uint flags=word(vertex,12u);
    return (flags&4u)!=0u&&(flags&8u)==0u;
#endif
}

bool alphaBlendSurface() {
#if ENTITY
    return alphaBlendFlag(triangleVertices().x);
#else
    uint baseVertex=triangleVertices().x;
    if(alphaBlendFlag(baseVertex)) return true;
    uint count=terrainLayerCount(baseVertex);
    uint first=terrainLayerStartWords(baseVertex)/16u;
    for(uint layer=0u;layer<count;layer++) {
        uint layerVertex=first+layer*4u+(baseVertex%4u);
        if(alphaBlendFlag(layerVertex)) return true;
    }
    return false;
#endif
}

bool alphaDiscardSurface() {
    return !alphaBlendSurface()&&alphaDiscardSurface(triangleVertices().x);
}

uint surfaceMaterial() {
#if ENTITY
    return word(triangleVertices().x,9u);
#else
    uvec3 v=triangleVertices();
    uint material=word(v.x,8u)&65535u;
    uint count=terrainLayerCount(v.x);
    if(count==0u) return material;
    vec3 b=vec3(1.0-barycentric.x-barycentric.y,barycentric);
    uint first=terrainLayerStartWords(v.x)/16u;
    for(uint layer=0u;layer<count;layer++) {
        uvec3 lv=first+layer*4u+(v%4u);
        vec4 surface=sampleOneSurface(lv,b);
        if(alphaDiscardSurface(lv.x)&&surface.a<0.5) continue;
        if(surface.a>0.0) material=word(lv.x,8u)&65535u;
    }
    return material;
#endif
}

bool twoSidedSurface() {
#if ENTITY
    return (entityFlags(triangleVertices().x)&16u)!=0u;
#else
    return (word(triangleVertices().x,12u)&2u)!=0u;
#endif
}

vec3 position(uint vertex) {
#if ENTITY
    return vec3(uintBitsToFloat(word(vertex,0u)),uintBitsToFloat(word(vertex,1u)),uintBitsToFloat(word(vertex,2u)));
#else
    uint a=word(vertex,0u),b=word(vertex,1u);
    return vec3(a&65535u,a>>16u,b&65535u)*(32.0/65536.0)-8.0;
#endif
}

float rayFootprintAt(float distance) {
    return max(hit.rayConeWidth+hit.rayConeSpread*distance,0.0);
}

vec4 sampleOneSurface(uvec3 v,vec3 b) {
    vec2 st=uv(v.x)*b.x+uv(v.y)*b.y+uv(v.z)*b.z;
#if ENTITY
    uint textureId=word(v.x,7u);
    vec2 atlasSize=vec2(textureSize(entityTextures[nonuniformEXT(textureId)],0));
#else
    vec2 atlasSize=vec2(textureSize(terrainTexture,0));
#endif
    vec3 edge0=mat3(gl_ObjectToWorldEXT)*(position(v.y)-position(v.x));
    vec3 edge1=mat3(gl_ObjectToWorldEXT)*(position(v.z)-position(v.x));
    float density=max(length((uv(v.y)-uv(v.x))*atlasSize)/max(length(edge0),1e-5),
                      length((uv(v.z)-uv(v.x))*atlasSize)/max(length(edge1),1e-5));
    float lod=clamp(log2(max(rayFootprintAt(gl_HitTEXT)*density,1.0)),0.0,8.0);
#if ENTITY
    vec4 texel=textureLod(entityTextures[nonuniformEXT(textureId)],st,lod);
#else
    vec4 texel=textureLod(terrainTexture,st,lod);
#endif
    vec4 tint=vertexColor(v.x)*b.x+vertexColor(v.y)*b.y+vertexColor(v.z)*b.z;
    return vec4(srgbToLinear(texel.rgb)*srgbToLinear(tint.rgb),texel.a*tint.a);
}

vec4 sampleSurface() {
    uvec3 v=triangleVertices();
    vec3 b=vec3(1.0-barycentric.x-barycentric.y,barycentric);
#if ENTITY
    return sampleOneSurface(v,b);
#else
    uint count=terrainLayerCount(v.x);
    if(count==0u) return sampleOneSurface(v,b);
    uint first=terrainLayerStartWords(v.x)/16u;
    vec4 composed=vec4(0.0);
    for(uint layer=0u;layer<count;layer++) {
        uvec3 lv=first+layer*4u+(v%4u);
        vec4 surface=sampleOneSurface(lv,b);
        if(alphaDiscardSurface(lv.x)&&surface.a<0.5) surface.a=0.0;
        composed.rgb=surface.rgb*surface.a+composed.rgb*(1.0-surface.a);
        composed.a=surface.a+composed.a*(1.0-surface.a);
    }
    return vec4(composed.rgb/max(composed.a,1e-5),composed.a);
#endif
}

vec3 interpolatedNormal(uvec3 v,vec3 geometricNormal) {
    vec3 b=vec3(1.0-barycentric.x-barycentric.y,barycentric);
    vec3 packedNormal=vec3(0.0);
#if ENTITY
    for(int i=0;i<3;i++) packedNormal+=b[i]*unpackSnorm4x8(word(v[i],6u)).xyz;
#else
    for(int i=0;i<3;i++) packedNormal+=b[i]*unpackSnorm4x8(word(v[i],7u)).xyz;
#endif
    if(dot(packedNormal,packedNormal)<=0.01) return geometricNormal;
    vec3 result=normalize(transpose(mat3(gl_WorldToObjectEXT))*packedNormal);
    if(dot(result,geometricNormal)<0.0) result=-result;
    return result;
}

void setSurfaceMaterial(uvec3 v,vec3 geometricNormal) {
    hit.geometricNormal=geometricNormal;
    hit.normal=interpolatedNormal(v,geometricNormal);
    hit.color=sampleSurface();
    hit.material=surfaceMaterial();
}
#endif
