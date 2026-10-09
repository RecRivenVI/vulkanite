package me.cortex.vulkanite.compat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One pack-declared Vulkan segment following a completed Iris composite pass. */
public record VulkanPassContract(
        Phase phase,
        String afterPass,
        List<Integer> colorReads,
        int colorWrite,
        Map<Integer, Long> storageMinimumBytes) {
    public enum Phase {
        COMPOSITE
    }

    public VulkanPassContract {
        Objects.requireNonNull(phase, "phase");
        if (afterPass == null || afterPass.isBlank())
            throw new IllegalArgumentException("Composite pass anchor must be named");
        if (colorWrite < 0)
            throw new IllegalArgumentException("Color output target must be non-negative");
        colorReads = List.copyOf(Objects.requireNonNull(colorReads, "colorReads"));
        if (new HashSet<>(colorReads).size() != colorReads.size()
                || colorReads.stream().anyMatch(id -> id == null || id < 0))
            throw new IllegalArgumentException(
                    "Color inputs must be distinct non-negative target IDs");
        if (colorReads.contains(colorWrite))
            throw new IllegalArgumentException(
                    "In-place read/write of one colortex is not supported");
        storageMinimumBytes =
                Map.copyOf(Objects.requireNonNull(storageMinimumBytes, "storageMinimumBytes"));
        for (var entry : storageMinimumBytes.entrySet())
            if (entry.getKey() < 0 || entry.getValue() <= 0)
                throw new IllegalArgumentException(
                        "Shared storage bindings and sizes must be positive");
    }
}
