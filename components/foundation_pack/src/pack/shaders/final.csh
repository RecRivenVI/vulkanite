#version 430
#include "/lib/exposure.glsl"
layout(local_size_x=64) in;
const ivec3 workGroups=ivec3(1,1,1);
uniform sampler2D colortex0;
layout(rgba32f) uniform image2D colorimg8;
uniform float frameTime;
uniform float sunAngle;
shared uint histogram[256];
shared uint validSamples;
const uint SAMPLE_AXIS=64u;
const uint SAMPLE_COUNT=SAMPLE_AXIS*SAMPLE_AXIS;
const float LOG_LUMINANCE_MIN=-20.0;
const float LOG_LUMINANCE_MAX=20.0;

void main() {
    uint lane=gl_LocalInvocationID.x;
    for(uint bin=lane;bin<256u;bin+=64u) histogram[bin]=0u;
    if(lane==0u) validSamples=0u;
    barrier();
    ivec2 size=textureSize(colortex0,0);
    vec4 previous=imageLoad(colorimg8,ivec2(0));
    float appliedEV=appliedExposureEV(previous);
    for(uint sampleIndex=lane;sampleIndex<SAMPLE_COUNT;sampleIndex+=64u) {
        vec2 cell=vec2(sampleIndex%SAMPLE_AXIS,sampleIndex/SAMPLE_AXIS);
        // Average scene-linear radiance BEFORE taking its logarithm. With
        // raw PT, log(single noisy sample) strongly underestimates luminance
        // and gives a different exposure from RR on the same scene.
        vec3 color=vec3(0.0);
        uint accepted=0u;
        for(uint sub=0u;sub<16u;sub++) {
            vec2 uv=(cell+(vec2(sub%4u,sub/4u)+0.5)/4.0)/float(SAMPLE_AXIS);
#if EXPOSURE_METERING == 1
            uv=0.5+(uv-0.5)*sqrt(0.2);
#endif
            ivec2 pixel=min(ivec2(uv*vec2(size)),size-1);
            vec3 value=texelFetch(colortex0,pixel,0).rgb*exp2(-appliedEV);
            if(any(isnan(value)) || any(isinf(value))) continue;
            color+=max(value,vec3(0.0));
            accepted++;
        }
        if(accepted==0u) continue;
        color/=float(accepted);
        float luminance=dot(color,vec3(0.2126,0.7152,0.0722));
        float normalized=clamp((log2(max(luminance,exp2(LOG_LUMINANCE_MIN)))-LOG_LUMINANCE_MIN)
                              /(LOG_LUMINANCE_MAX-LOG_LUMINANCE_MIN),0.0,1.0);
        atomicAdd(histogram[min(uint(normalized*256.0),255u)],1u);
        atomicAdd(validSamples,1u);
    }
    barrier();
    if(lane==0u) {
        uint lowCount=uint(float(EXPOSURE_LOW_PERCENT)*0.01*float(validSamples));
        uint highCount=max(lowCount+1u,uint(float(EXPOSURE_HIGH_PERCENT)*0.01*float(validSamples)));
        uint highlightCount=max(1u,uint(0.95*float(validSamples)));
        uint cumulative=0u,used=0u;
        float sumLog=0.0,highlightLog=LOG_LUMINANCE_MIN;
        for(uint bin=0u;bin<256u;bin++) {
            uint next=cumulative+histogram[bin];
            float logLum=mix(LOG_LUMINANCE_MIN,LOG_LUMINANCE_MAX,(float(bin)+0.5)/256.0);
            if(cumulative<highlightCount && next>=highlightCount) highlightLog=logLum;
            uint first=max(cumulative,lowCount),last=min(next,highCount);
            if(last>first) { sumLog+=logLum*float(last-first); used+=last-first; }
            cumulative=next;
        }
        float averageLog=used>0u?sumLog/float(used):LOG_LUMINANCE_MIN;
        float target=exposureTargetEV(averageLog,highlightLog,sunAngle);
        if(validSamples==0u) target=appliedEV;
        float nextEV=validExposure(previous)?adaptExposureEV(appliedEV,target,frameTime):target;
        if(AUTO_EXPOSURE==0) nextEV=0.0;
        imageStore(colorimg8,ivec2(0),vec4(nextEV,averageLog,appliedEV,1.0));
    }
}
