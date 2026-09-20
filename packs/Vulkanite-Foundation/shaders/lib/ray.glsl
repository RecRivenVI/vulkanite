#ifndef FOUNDATION_COMMON
#define FOUNDATION_COMMON
#include "/lib/material.glsl"
#include "/lib/surface.glsl"
layout(location=0) rayPayloadEXT Surface hit;
layout(set=0,binding=0,std140) uniform Camera {
    vec3 corners[4];
    mat4 inverseView;
    uint frame;
    uint flags;
    // std140 aligns this metadata vector to byte 144; FrameAbi size is 160 bytes.
    uvec4 vulkaniteFrameAbi;
} camera;
layout(set=0,binding=1) uniform accelerationStructureEXT scene;
layout(set=0,binding=6,rgba32f) writeonly uniform image2D outputImage;
layout(set=2,binding=12,std430) readonly buffer VulkaniteFrameBridge {
    uvec4 header;
    mat4 projection;
    mat4 modelView;
    mat4 previousProjection;
    mat4 previousModelView;
    vec4 cameraAndFrameTime;
    vec4 previousCameraAndRain;
    ivec4 worldState;
    vec4 sun;
    vec4 moon;
    vec4 renderSize;
} irisFrame;
#endif
