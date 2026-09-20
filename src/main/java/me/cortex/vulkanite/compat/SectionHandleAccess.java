package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.acceleration.SectionHandle;

/** Implemented only by the Sodium section adapter. */
public interface SectionHandleAccess {
    SectionHandle vulkanite$sectionHandle();
}
