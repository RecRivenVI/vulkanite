#ifndef FOUNDATION_VISIBILITY
#define FOUNDATION_VISIBILITY
const uint VISIBILITY_PRIMARY_CAMERA=1u;
const uint VISIBILITY_VIEW_MODEL_ONLY=2u;
const uint RAY_MASK_PRIMARY=1u;
const uint RAY_MASK_VIEW_MODEL=2u;
const uint RAY_MASK_WORLD=4u;

uint rayMask(uint visibility) {
    if((visibility&VISIBILITY_VIEW_MODEL_ONLY)!=0u) return RAY_MASK_VIEW_MODEL;
    if((visibility&VISIBILITY_PRIMARY_CAMERA)!=0u) return RAY_MASK_PRIMARY;
    return RAY_MASK_WORLD;
}

bool cameraSidednessRequired(uint visibility) {
    return (visibility&(VISIBILITY_PRIMARY_CAMERA|VISIBILITY_VIEW_MODEL_ONLY))!=0u;
}

// Keep camera primary rays front-face aware. World bounces and shadow rays are
// two-sided so the back faces of closed geometry still occlude and shade.
bool visibleTriangleSide(bool twoSided,bool backFacing,uint visibility) {
    return !cameraSidednessRequired(visibility)||!backFacing||twoSided;
}
#endif
