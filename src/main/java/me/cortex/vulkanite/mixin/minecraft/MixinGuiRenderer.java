package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import me.cortex.vulkanite.client.rendering.UiCompositor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public abstract class MixinGuiRenderer {
    @Shadow @Final private List<?> draws;
    @Shadow private int firstDrawIndexAfterBlur;
    @Unique private final UiCompositor vulkanite$ui = new UiCompositor();

    @WrapMethod(method = "draw")
    private void vulkanite$composeGui(Operation<Void> original) {
        var renderer = Minecraft.getInstance().gameRenderer;
        var main = renderer.mainRenderTarget();
        vulkanite$ui.begin(main, renderer.gameRenderState().shouldRenderLevel);
        if (draws.size() > firstDrawIndexAfterBlur) vulkanite$ui.backgroundEffect("menu-blur");
        boolean completed = false;
        try {
            original.call();
            completed = true;
        } finally {
            vulkanite$ui.end(main, completed);
        }
    }

    @WrapMethod(method = "executeDrawRange")
    private void vulkanite$splitDraws(Supplier<String> label, RenderTarget main, GpuBufferSlice transforms,
                                     int start, int end, Operation<Void> original) {
        if (!vulkanite$ui.active()) { original.call(label, main, transforms, start, end); return; }
        RenderTarget reference = vulkanite$ui.reference(main);
        if (reference != null) original.call(label, reference, transforms, start, end);
        for (int cursor = start; cursor < end;) {
            var pipeline = ((GuiDrawAccessor)draws.get(cursor)).vulkanite$pipeline();
            boolean separable = UiCompositor.separable(pipeline);
            int next = cursor + 1;
            while (next < end && UiCompositor.separable(((GuiDrawAccessor)draws.get(next)).vulkanite$pipeline()) == separable) next++;
            if (separable) {
                RenderTarget layer = vulkanite$ui.beginLayer(main);
                original.call(label, layer, transforms, cursor, next);
                vulkanite$ui.composeLayer(main);
            } else {
                for (int i = cursor; i < next; i++) vulkanite$ui.direct(((GuiDrawAccessor)draws.get(i)).vulkanite$pipeline());
                original.call(label, main, transforms, cursor, next);
            }
            cursor = next;
        }
        vulkanite$ui.compare(main);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vulkanite$closeComposition(CallbackInfo ci) { vulkanite$ui.close(); }
}
