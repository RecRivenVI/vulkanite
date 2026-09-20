#ifndef FOUNDATION_ATMOSPHERE
#define FOUNDATION_ATMOSPHERE
// Clear spherical atmosphere, single scattering. Distances and coefficients use metres.
const float EARTH_RADIUS=6360000.0, ATMOSPHERE_RADIUS=6440000.0;
const vec3 BETA_R=vec3(5.8e-6,13.5e-6,33.1e-6);
const vec3 BETA_M=vec3(2.0e-6);
const float SUN_RADIUS=0.00465;
const float SUN_COS=cos(SUN_RADIUS);
const float SUN_OMEGA=2.0*PI*(1.0-SUN_COS);
const vec3 SOLAR_IRRADIANCE=vec3(1.0);
vec2 sphereInterval(vec3 o,vec3 d,float radius) {
    float b=dot(o,d), c=dot(o,o)-radius*radius;
    float discriminant=b*b-c;
    if(discriminant<0.0) return vec2(-1.0);
    float h=sqrt(discriminant); return vec2(-b-h,-b+h);
}
vec3 planetPosition(float worldY) { return vec3(0.0,EARTH_RADIUS+max(2.0,worldY-63.0+2.0),0.0); }
vec2 density(vec3 p) {
    float altitude=max(0.0,length(p)-EARTH_RADIUS);
    return exp(-altitude/vec2(8000.0,1200.0));
}
vec3 extinction(vec2 opticalDepth) { return BETA_R*opticalDepth.x+BETA_M*opticalDepth.y; }
vec3 solarTransmission(vec3 p,vec3 sunDirection) {
    vec2 ground=sphereInterval(p,sunDirection,EARTH_RADIUS);
    if(ground.x>0.0) return vec3(0.0);
    float stepLength=max(0.0,sphereInterval(p,sunDirection,ATMOSPHERE_RADIUS).y)/4.0;
    vec2 opticalDepth=vec2(0.0);
    for(int i=0;i<4;i++) opticalDepth+=density(p+sunDirection*((float(i)+0.5)*stepLength))*stepLength;
    return exp(-extinction(opticalDepth));
}
vec3 skyRadiance(vec3 d,vec3 sunDirection,float worldY) {
    vec3 origin=planetPosition(worldY);
    float distance=sphereInterval(origin,d,ATMOSPHERE_RADIUS).y;
    vec2 ground=sphereInterval(origin,d,EARTH_RADIUS);
    if(ground.x>0.0) distance=min(distance,ground.x);
    float stepLength=max(0.0,distance)/8.0;
    float mu=dot(d,sunDirection), g=0.76;
    float phaseR=3.0/(16.0*PI)*(1.0+mu*mu);
    float phaseM=(1.0-g*g)/(4.0*PI*pow(1.0+g*g-2.0*g*mu,1.5));
    vec2 opticalDepth=vec2(0.0); vec3 scattering=vec3(0.0);
    for(int i=0;i<8;i++) {
        vec3 p=origin+d*((float(i)+0.5)*stepLength);
        vec2 local=density(p)*stepLength;
        vec3 transmittance=exp(-extinction(opticalDepth+local*0.5))*solarTransmission(p,sunDirection);
        scattering+=transmittance*(BETA_R*(local.x*phaseR)+BETA_M*(local.y*phaseM));
        opticalDepth+=local;
    }
    return scattering*SOLAR_IRRADIANCE;
}
vec3 sunRadiance(vec3 d,vec3 sunDirection,float worldY) {
    if(dot(d,sunDirection)<SUN_COS) return vec3(0.0);
    return SOLAR_IRRADIANCE/SUN_OMEGA*solarTransmission(planetPosition(worldY),d);
}
vec3 stellarRadiance(vec3 d,float worldY) {
    vec3 p=planetPosition(worldY);
    if(sphereInterval(p,d,EARTH_RADIUS).x>0.0) return vec3(0.0);
    // Integrated unresolved stellar background is incoming environment radiance,
    // not an unoccluded ambient term applied to surfaces or caves.
    return vec3(1.0e-8)*solarTransmission(p,d);
}
#endif
