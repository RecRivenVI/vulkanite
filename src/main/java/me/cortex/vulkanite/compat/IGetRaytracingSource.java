package me.cortex.vulkanite.compat;

public interface IGetRaytracingSource {
    RaytracingShaderSource[] getRaytracingSource();
    /** Conservative default for adapters that do not publish Vulkanite requirements. */
    default PackCapabilities vulkanite$capabilities() { return PackCapabilities.OPENGL; }
}
