#version 430
layout(local_size_x=1,local_size_y=1,local_size_z=1) in;
const ivec3 workGroups=ivec3(1,1,1);

layout(std430,binding=12) buffer VulkaniteFrameBridge {
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
} vulkaniteFrame;

uniform mat4 gbufferProjection;
uniform mat4 gbufferModelView;
uniform mat4 gbufferPreviousProjection;
uniform mat4 gbufferPreviousModelView;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform vec3 sunPosition;
uniform vec3 moonPosition;
uniform int moonPhase;
uniform float frameTime;
uniform float rainStrength;
uniform float viewWidth;
uniform float viewHeight;
uniform int frameCounter;
uniform int worldTime;
uniform int worldDay;
uniform int isEyeInWater;

bool finiteFloat(float value) { return !isnan(value) && !isinf(value); }
bool finiteVec4(vec4 value) { return all(not(isnan(value))) && all(not(isinf(value))); }
bool finiteMat4(mat4 value) {
    for(int column=0;column<4;column++) if(!finiteVec4(value[column])) return false;
    return true;
}

void main() {
    if(any(notEqual(gl_GlobalInvocationID,uvec3(0)))) return;
    // Invalidate first; publish validity only after all Iris values are written.
    vulkaniteFrame.header=uvec4(2u,0u,0u,1u);
    vulkaniteFrame.projection=gbufferProjection;
    vulkaniteFrame.modelView=gbufferModelView;
    vulkaniteFrame.previousProjection=gbufferPreviousProjection;
    vulkaniteFrame.previousModelView=gbufferPreviousModelView;
    vulkaniteFrame.cameraAndFrameTime=vec4(cameraPosition,frameTime);
    vulkaniteFrame.previousCameraAndRain=vec4(previousCameraPosition,rainStrength);
    vulkaniteFrame.worldState=ivec4(frameCounter,worldTime,worldDay,isEyeInWater);
    vulkaniteFrame.sun=vec4(sunPosition,0.0);
    vulkaniteFrame.moon=vec4(moonPosition,float(moonPhase));
    vulkaniteFrame.renderSize=vec4(viewWidth,viewHeight,1.0/max(viewWidth,1.0),1.0/max(viewHeight,1.0));
    bool cameraValid=finiteMat4(gbufferProjection) && finiteMat4(gbufferModelView)
            && abs(gbufferProjection[0][0])>0.0001 && abs(determinant(gbufferModelView))>0.0001
            && finiteVec4(vulkaniteFrame.cameraAndFrameTime)
            && finiteVec4(vulkaniteFrame.renderSize) && viewWidth>0.0 && viewHeight>0.0;
    bool temporalValid=finiteMat4(gbufferPreviousProjection) && finiteMat4(gbufferPreviousModelView)
            && abs(gbufferPreviousProjection[0][0])>0.0001
            && abs(determinant(gbufferPreviousModelView))>0.0001
            && finiteVec4(vulkaniteFrame.previousCameraAndRain) && finiteFloat(frameTime) && frameTime>=0.0;
    bool celestialValid=finiteVec4(vulkaniteFrame.sun) && finiteVec4(vulkaniteFrame.moon)
            && dot(sunPosition,sunPosition)>1.0 && dot(moonPosition,moonPosition)>1.0
            && moonPhase>=0 && moonPhase<=7;
    bool environmentValid=finiteFloat(rainStrength) && rainStrength>=0.0 && rainStrength<=1.0
            && isEyeInWater>=0 && isEyeInWater<=3;
    uint validMask=(cameraValid?1u:0u)|(temporalValid?2u:0u)
            |(celestialValid?4u:0u)|(environmentValid?8u:0u);
    memoryBarrierBuffer();
    // ABI v2 adds raw Iris moon phase in moon.w without changing the buffer layout.
    vulkaniteFrame.header=uvec4(2u,validMask,0u,1u);
}
