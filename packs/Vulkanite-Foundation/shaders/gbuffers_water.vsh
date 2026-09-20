#version 430 compatibility
out vec2 texCoord;
in vec4 mc_Entity;
flat out float materialIdentity;
void main(){gl_Position=ftransform();texCoord=gl_MultiTexCoord0.xy;materialIdentity=mc_Entity.x;}
