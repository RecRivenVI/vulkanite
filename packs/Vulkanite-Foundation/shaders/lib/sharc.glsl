#ifndef VULKANITE_SHARC_GLSL
#define VULKANITE_SHARC_GLSL

#define SHARC_ENABLE_GLSL 1
#define SHARC_UPDATE 1
#define SHARC_QUERY 1
#define SHARC_USE_FP16 0
#define SHARC_ENABLE_SH_ENCODING 0
#define SHARC_ENABLE_RESPONSIVE_LIGHTING 0
#define HASH_GRID_COMPACT 1
#define HASH_GRID_ENABLE_64_BIT_ATOMICS 0
#include "/lib/sharc/SharcGlslHelpers.h"
#include "/lib/sharc/SharcCommon.h"

SharcParameters vulkaniteSharcParameters() {
    SharcParameters result;
    result.hashGridParameters.cameraPosition=camera.inverseView[3].xyz;
    result.hashGridParameters.logarithmBase=camera.sharcGrid.z;
    result.hashGridParameters.sceneScale=camera.sharcGrid.x;
    result.hashGridParameters.levelBias=camera.sharcGrid.w;
    result.hashGridData.capacity=camera.sharcConfig.x;
    result.hashGridData.hashEntriesBuffer=RWStructuredBuffer_uint(camera.sharcHashAddress);
    result.radianceScale=camera.sharcGrid.y;
    result.accumulationBuffer=RWStructuredBuffer_SharcAccumulationData(camera.sharcAccumulationAddress);
    result.resolvedBuffer=RWStructuredBuffer_SharcPackedData(camera.sharcResolvedAddress);
    return result;
}

SharcHitData vulkaniteSharcHit(Surface surface) {
    SharcHitData result;
    result.positionWorld=surface.position;
    result.normalWorld=surface.normal;
    return result;
}

#endif
