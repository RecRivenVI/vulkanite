package me.cortex.vulkanite.mixin.minecraft;

import java.util.List;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves the normal producer's Draw-to-RenderType mapping before the group is cleared. */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class MixinRenderTypeFeatureGroup {
    @Shadow @Final private List<StagedVertexBuffer.Draw> draws;
    @Shadow @Final private List<PreparedRenderType> drawRenderTypes;

    @Inject(method = "getOrAddDraw", at = @At("RETURN"))
    private void vulkanite$rememberDraw(
            RenderType type, CallbackInfoReturnable<StagedVertexBuffer.Draw> cir) {
        int index = draws.indexOf(cir.getReturnValue());
        if (index >= 0 && index < drawRenderTypes.size()) {
            ProducedDraws.registerRenderType(
                    cir.getReturnValue(), type, drawRenderTypes.get(index));
        }
    }
}
