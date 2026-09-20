#ifndef FOUNDATION_VISIBILITY
#define FOUNDATION_VISIBILITY
// visibility: 0 world/shadow/secondary, 1 primary camera, 2 internal camera-viewmodel dielectric.
bool visibleToRay(uint flags,uint visibility) {
    if((flags&2u)!=0u && (visibility&1u)!=0u) return false;
    if((flags&4u)!=0u && visibility==0u) return false;
    return true;
}
#endif
