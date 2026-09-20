// Adapted from KhronosGroup/ToneMapping PBR_Neutral/pbrNeutral.glsl (Apache-2.0).
// Copyright 2024 The Khronos Group Inc. License: licenses/Khronos-ToneMapping.txt.
// Modified: renamed function and desaturation=0.01, matching the reference's restrained highlight look.
vec3 neutralDisplay(vec3 color) {
    float darkest=min(color.r,min(color.g,color.b));
    color-=darkest<0.08?darkest-6.25*darkest*darkest:0.04;
    float peak=max(color.r,max(color.g,color.b));
    if(peak<0.76) return color;
    float compressed=1.0-0.24*0.24/(peak-0.52);
    color*=compressed/peak;
    return mix(color,vec3(compressed),1.0-1.0/(1.0+0.01*(peak-compressed)));
}
