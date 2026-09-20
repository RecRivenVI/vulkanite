#version 430
layout(local_size_x=64) in;
const ivec3 workGroups=ivec3(1,1,1);
uniform sampler2D colortex0;
layout(rgba32f) uniform image2D colorimg8;
uniform float frameTime;
uniform int frameCounter;
shared float logLuminance[64];
void main() {
    uint lane=gl_LocalInvocationID.x;
    ivec2 size=textureSize(colortex0,0);
    vec4 previous=imageLoad(colorimg8,ivec2(0));
    float appliedEV=previous.w==1.0 && !isnan(previous.x) && !isinf(previous.x)?clamp(previous.x,-8.0,24.0):0.0;
    float total=0.0;
    // Meter only the world before GUI, in log space so the solar disk does not dominate.
    for(uint sampleIndex=lane;sampleIndex<1024u;sampleIndex+=64u) {
        vec2 uv=(vec2(sampleIndex%32u,sampleIndex/32u)+0.5)/32.0;
        uv=0.5+(uv-0.5)*sqrt(0.2); // Central 20% area, matching the reference's default metering.
        vec3 color=max(texelFetch(colortex0,min(ivec2(uv*vec2(size)),size-1),0).rgb,vec3(0.0));
        total+=log2(max(dot(color,vec3(0.2126,0.7152,0.0722))*exp2(-appliedEV),1e-10));
    }
    logLuminance[lane]=total; barrier();
    for(uint step=32u;step>0u;step>>=1u) {
        if(lane<step) logLuminance[lane]+=logLuminance[lane+step];
        barrier();
    }
    if(lane==0u) {
        // Finite camera sensitivity: don't turn starlit terrain into daylight-gray.
        float target=clamp(log2(0.18)-logLuminance[0]/1024.0,-8.0,22.0);
        vec4 old=imageLoad(colorimg8,ivec2(0));
        float response=target<old.x?4.0:1.2;
        float blend=1.0-exp(-clamp(frameTime,0.001,1.0)*response);
        float exposure=(old.w!=1.0||isnan(old.x)||isinf(old.x)||frameCounter<2)?target:mix(old.x,target,blend);
        imageStore(colorimg8,ivec2(0),vec4(exposure,logLuminance[0]/1024.0,appliedEV,1.0));
    }
}
