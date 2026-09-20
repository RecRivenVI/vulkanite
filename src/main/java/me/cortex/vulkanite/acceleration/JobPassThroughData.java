package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.memory.VBuffer;

import java.util.List;

public record JobPassThroughData(SectionHandle section, long revision, long generation,
                                 List<VBuffer> geometryBuffers) {

}
