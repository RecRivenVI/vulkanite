package me.cortex.vulkanite.mixin.sodium;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.acceleration.SectionMeshUpdate;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.ActivePack;
import me.cortex.vulkanite.compat.SodiumSectionInput;
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
        var runtime = Vulkanite.getIfInitialized();
        if (runtime != null) runtime.destroy();
    }

    @Redirect(method = "processChunkBuildResults", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"))
    private void uploadGeometry(RenderRegionManager regions, Collection<BuilderTaskOutput> outputs, UniformBufferManager uniforms) {
        var updates = new ArrayList<SectionMeshUpdate>();
        var state = ActivePack.state();
        Throwable failure = null;
        try {
            for (var output : outputs) {
                if (state.capabilities().sceneGeometry() && output instanceof ChunkBuildOutput build
                        && ((IAccelerationBuildResult) build).vulkanite$generation() == state.generation()) {
                    var update = SodiumSectionInput.copy(build);
                    if (update != null) updates.add(update);
                }
            }
            if (!updates.isEmpty()) Vulkanite.getInstance().upload(updates);
        } catch (Throwable inputFailure) {
            failure = inputFailure;
        }
        // Sodium still owns every original NativeBuffer. Its upload must run exactly once
        // even if our copy or Vulkan upload fails, so its normal release path can run.
        for (var update : updates) {
            try { update.close(); }
            catch (Throwable closeFailure) { failure = vulkanite$combine(failure, closeFailure); }
        }
        try { regions.uploadResults(outputs, uniforms); }
        catch (Throwable sodiumFailure) { failure = vulkanite$combine(failure, sodiumFailure); }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure != null) throw new IllegalStateException("Section upload failed", failure);
    }

    private static Throwable vulkanite$combine(Throwable primary, Throwable additional) {
        if (primary == null) return additional;
        if (primary != additional) primary.addSuppressed(additional);
        return primary;
    }
}
