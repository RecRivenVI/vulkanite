package me.cortex.vulkanite.mixin.sodium;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import java.util.Collection;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager {
    @Inject(method = "destroy", at = @At("TAIL"))
    private void onDestroy(CallbackInfo ci) {
        Vulkanite.INSTANCE.destroy();
    }

    @Redirect(method = "processChunkBuildResults", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"))
    private void uploadGeometry(RenderRegionManager regions, Collection<BuilderTaskOutput> outputs, UniformBufferManager uniforms) {
        var builds = new ArrayList<ChunkBuildOutput>();
        for (var output : outputs) {
            if (output instanceof ChunkBuildOutput build && ((IAccelerationBuildResult) build).getAccelerationGeometryData() != null) {
                builds.add(build);
            }
        }
        if (!builds.isEmpty()) Vulkanite.INSTANCE.upload(builds);
        regions.uploadResults(outputs, uniforms);
    }
}
