#ifndef FOUNDATION_EXPOSURE_GLSL
#define FOUNDATION_EXPOSURE_GLSL
#define AUTO_EXPOSURE 1 // [0 1]
#define EXPOSURE_EV 0 // [-4 -3 -2 -1 0 1 2 3 4]
#define EXPOSURE_METERING 0 // [0 1]
#define EXPOSURE_MIN_EV -8 // [-16 -12 -8 -4 0]
#define EXPOSURE_MAX_EV 8 // [0 2 4 6 8 10 12 16]
#define EXPOSURE_LOW_PERCENT 5 // [0 1 5 10 20]
#define EXPOSURE_HIGH_PERCENT 95 // [80 90 95 98 99 100]
#define EXPOSURE_HIGHLIGHTS 0.25 // [0.0 0.25 0.5 0.75 1.0]
#define EXPOSURE_BRIGHT_SPEED 3.0 // [0.5 1.0 2.0 3.0 5.0 10.0]
#define EXPOSURE_DARK_SPEED 1.0 // [0.25 0.5 1.0 2.0 3.0 5.0]
#define EXPOSURE_NIGHT_EV -2 // [-4 -3 -2 -1 0]

// ABI of the 1x1 RGBA32F exposure target, shared with ExposureMath.java:
// x = exposure EV for the next PT frame / current display
// y = measured scene-linear log2 luminance
// z = exposure EV already applied to the current HDR color
// w = 1 only when valid. Invalid/new targets apply unity exposure.
// Hard bounds are independent of adaptation limits: changing limits must
// never change how an already exposed frame is decoded.
const float EXPOSURE_ABI_MIN_EV=-16.0;
const float EXPOSURE_ABI_MAX_EV=16.0;
bool validExposure(vec4 history) {
    return history.w==1.0 && !isnan(history.x) && !isinf(history.x);
}
float appliedExposureEV(vec4 history) {
    return validExposure(history)?clamp(history.x,EXPOSURE_ABI_MIN_EV,EXPOSURE_ABI_MAX_EV):0.0;
}
float exposureTargetEV(float averageLog,float highlightLog,float sunPhase) {
    if(AUTO_EXPOSURE==0) return 0.0;
    // Keep dark nights darker than daytime without suppressing a lit room
    // at night. Daytime interiors retain the full adaptation range.
    float night=smoothstep(0.0,0.15,-sin(sunPhase*6.28318530718));
    float dimScene=1.0-smoothstep(-6.0,-2.0,averageLog);
    float target=log2(0.18)-averageLog+float(EXPOSURE_NIGHT_EV)*night*dimScene;
    // Percentile, not the brightest pixel: isolated fireflies/emitters must
    // not close the iris. Soft protection reduces exposure by at most 4 EV.
    float highlightLimit=2.0-highlightLog;
    target-=float(EXPOSURE_HIGHLIGHTS)*clamp(target-highlightLimit,0.0,4.0);
    float minimum=clamp(float(EXPOSURE_MIN_EV),EXPOSURE_ABI_MIN_EV,EXPOSURE_ABI_MAX_EV);
    float maximum=clamp(max(float(EXPOSURE_MAX_EV),minimum),minimum,EXPOSURE_ABI_MAX_EV);
    return clamp(target,minimum,maximum);
}
float adaptExposureEV(float previous,float target,float seconds) {
    float speed=target<previous?float(EXPOSURE_BRIGHT_SPEED):float(EXPOSURE_DARK_SPEED);
    return mix(previous,target,1.0-exp(-clamp(seconds,0.0,1.0)*speed));
}
#endif
