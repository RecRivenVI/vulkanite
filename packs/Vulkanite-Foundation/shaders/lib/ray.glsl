#ifndef FOUNDATION_COMMON
#define FOUNDATION_COMMON
#include "/lib/material.glsl"
#include "/lib/surface.glsl"
layout(location=0) rayPayloadEXT Surface hit;
layout(set=0,binding=0,std140) uniform Camera {
    vec3 corners[4]; mat4 inverseView; vec4 sun; vec4 moon; uint frame; uint flags;
    mat4 previousViewProjection; mat4 viewProjection; mat4 worldToView; mat4 viewToClip;
    vec4 jitterAndRenderSize; vec4 outputSizeResetEnabled;
    vec4 radianceExposure;
    uvec2 sharcHashAddress; uvec2 sharcAccumulationAddress; uvec2 sharcResolvedAddress;
    uvec4 sharcConfig; vec4 sharcGrid;
} camera;
layout(set=0,binding=1) uniform accelerationStructureEXT scene;
layout(set=0,binding=6,rgba32f) writeonly uniform image2D outputImages[16];
layout(set=0,binding=8,rgba32f) writeonly uniform image2D reconstructionColor;
layout(set=0,binding=9,r32f) writeonly uniform image2D reconstructionDepth;
layout(set=0,binding=10,rg32f) writeonly uniform image2D reconstructionMotion;
layout(set=0,binding=11,rgba16f) writeonly uniform image2D reconstructionDiffuse;
layout(set=0,binding=12,rgba16f) writeonly uniform image2D reconstructionSpecular;
layout(set=0,binding=13,rgba16f) writeonly uniform image2D reconstructionNormalRoughness;
layout(set=0,binding=14,r32f) writeonly uniform image2D reconstructionSpecularDistance;
layout(set=0,binding=15,r8) writeonly uniform image2D reconstructionHistoryBias;
#endif

