package me.cortex.vulkanite.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.vulkanite.compat.IGetRaytracingSource;
import me.cortex.vulkanite.compat.PackResourceScope;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Iris.class, remap = false)
public abstract class MixinIris {
    @WrapOperation(
            method = "createPipeline",
            at = @At(value = "NEW", target = "net/irisshaders/iris/pipeline/IrisRenderingPipeline"))
    private static IrisRenderingPipeline vulkanite$resourceScope(
            ProgramSet set, Operation<IrisRenderingPipeline> original) {
        try (var scope =
                PackResourceScope.enter(((IGetRaytracingSource) set).vulkanite$capabilities())) {
            return original.call(set);
        }
    }
}
