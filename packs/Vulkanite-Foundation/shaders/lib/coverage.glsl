#ifndef FOUNDATION_COVERAGE
#define FOUNDATION_COVERAGE
uint coverageHash(uint value) {
    value ^= value >> 16u; value *= 0x7feb352du;
    value ^= value >> 15u; value *= 0x846ca68bu;
    return value ^ (value >> 16u);
}
bool covered(float alpha,uint seed,uint primitive,uint instance) {
    uint bits=coverageHash(seed ^ primitive*1973u ^ instance*9277u);
    return float(bits>>8u)*(1.0/16777216.0)<clamp(alpha,0.0,1.0);
}
#endif
