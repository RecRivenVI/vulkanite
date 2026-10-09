package me.cortex.vulkanite.acceleration;

import java.util.List;
import me.cortex.vulkanite.lib.memory.VBuffer;

public record JobPassThroughData(
        SectionHandle section, long revision, long generation, List<VBuffer> geometryBuffers) {}
