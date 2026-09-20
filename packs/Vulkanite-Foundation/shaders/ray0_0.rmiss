#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_nonuniform_qualifier : require
#include "/lib/surface.glsl"
layout(location=0) rayPayloadInEXT Surface hit;
void main(){ hit.distance=-1.0; }
