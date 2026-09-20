package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.SodiumResultAdapter;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import me.cortex.vulkanite.compat.ActivePack;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class MixinChunkRenderRebuildTask {
    @Unique private ActivePack.State vulkanite$buildState;

    @Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", at = @At("HEAD"))
    private void beginCapture(ChunkBuildContext context, CancellationToken cancellation, CallbackInfoReturnable<ChunkBuildOutput> cir) {
        vulkanite$buildState = ActivePack.state();
    }
    @Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", at = @At("TAIL"))
    private void performExtraBuild(ChunkBuildContext buildContext, CancellationToken cancellationToken, CallbackInfoReturnable<ChunkBuildOutput> cir) {
        if (vulkanite$buildState.capabilities().sceneGeometry() && vulkanite$buildState == ActivePack.state()) {
            var buildResult = cir.getReturnValue();
            if (buildResult == null) return;
            ((IAccelerationBuildResult) buildResult).setVertexFormat(((VertexFormatAccessor) buildContext.buffers).getVertexType());
            ((IAccelerationBuildResult) buildResult).vulkanite$generation(vulkanite$buildState.generation());
            SodiumResultAdapter.compute(buildResult);
        }
    }
}
