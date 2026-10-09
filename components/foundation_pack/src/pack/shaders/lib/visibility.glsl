#ifndef FOUNDATION_VISIBILITY
#define FOUNDATION_VISIBILITY
const uint VISIBILITY_PRIMARY_CAMERA=1u;
const uint VISIBILITY_VIEW_MODEL_ONLY=2u;

// EntityFrame flags occupy the low 16 bits of entity word 8. Terrain flags have
// a separate layout and are intentionally never decoded by this helper.
bool entityVisibleToRay(uint flags,uint visibility) {
    bool viewModel=(flags&2u)!=0u;
    if((visibility&VISIBILITY_VIEW_MODEL_ONLY)!=0u) return viewModel;
    if(viewModel) return false;
    return (flags&1u)==0u||(visibility&VISIBILITY_PRIMARY_CAMERA)==0u;
}

bool worldGeometryVisible(uint visibility) {
    return (visibility&VISIBILITY_VIEW_MODEL_ONLY)==0u;
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
