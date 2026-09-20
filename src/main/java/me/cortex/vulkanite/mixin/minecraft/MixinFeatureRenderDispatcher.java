package me.cortex.vulkanite.mixin.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.class)
public abstract class MixinFeatureRenderDispatcher implements me.cortex.vulkanite.compat.FeatureDispatcherAccess {
    @Shadow @Final private ModelManager modelManager;
    @Shadow @Final private AtlasManager atlasManager;
    @Shadow @Final private Font font;
    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow @Final private StagedVertexBuffer stagedVertexBuffer;
    @Accessor("featureRenderers") public abstract FeatureRendererMap vulkanite$renderers();
    public FeatureFrameContext vulkanite$context() {
        var mc = Minecraft.getInstance();
        return new FeatureFrameContext(gameRenderState.optionsRenderState, font,
                modelManager.getBlockStateModelSet(), mc.getBlockColors(), mc.getTextureManager(),
                atlasManager, mc.gameRenderer.lightmap(), stagedVertexBuffer);
    }
}
