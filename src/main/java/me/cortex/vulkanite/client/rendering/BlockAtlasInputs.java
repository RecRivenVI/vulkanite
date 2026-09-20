package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.compat.EntityFrame;

import java.util.Objects;
import java.util.function.Supplier;

/** Late-bound texture inputs; producer-specific lookup stays outside the renderer. */
public record BlockAtlasInputs(Supplier<VImage> albedo, Supplier<VImage> normal,
                               Supplier<VImage> specular, Supplier<EntityFrame.SamplerState> sampler,
                               Runnable refresh) {
    public BlockAtlasInputs {
        Objects.requireNonNull(albedo, "albedo");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(specular, "specular");
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(refresh, "refresh");
    }
}
