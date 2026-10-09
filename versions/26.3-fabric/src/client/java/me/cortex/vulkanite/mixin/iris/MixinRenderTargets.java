package me.cortex.vulkanite.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.*;
import me.cortex.vulkanite.compat.PackCapabilities;
import me.cortex.vulkanite.compat.PackResourceScope;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderTargets.class, remap = false)
public abstract class MixinRenderTargets {
    @Unique private final PackCapabilities vulkanite$capabilities = PackResourceScope.current();

    // Some attachments are allocated lazily after pipeline construction has finished.
    @WrapMethod(method = "getOrCreate")
    private RenderTarget scopedTarget(int index, Operation<RenderTarget> original) {
        try (var scope = PackResourceScope.enter(vulkanite$capabilities)) {
            return original.call(index);
        }
    }

    @Shadow @Final private RenderTarget[] targets;
    @Shadow @Final private List<GlFramebuffer> ownedFramebuffers;
    @Unique private int[] vulkanite$oldTextures;

    @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
    private void rememberAttachments(CallbackInfoReturnable<Boolean> cir) {
        if (!vulkanite$capabilities.needsVulkan()) return;
        vulkanite$oldTextures = new int[targets.length * 2];
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != null) {
                vulkanite$oldTextures[i * 2] = targets[i].getMainTexture();
                vulkanite$oldTextures[i * 2 + 1] = targets[i].getAltTexture();
            }
        }
    }

    @Inject(method = "resizeIfNeeded", at = @At("RETURN"))
    private void reattachSharedTextures(CallbackInfoReturnable<Boolean> cir) {
        if (vulkanite$oldTextures == null || !cir.getReturnValue()) return;
        Map<Integer, Integer> replacements = new HashMap<>();
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != null && vulkanite$oldTextures[i * 2] != 0) {
                replacements.put(vulkanite$oldTextures[i * 2], targets[i].getMainTexture());
                replacements.put(vulkanite$oldTextures[i * 2 + 1], targets[i].getAltTexture());
            }
        }
        // Iris normally resizes mutable storage without changing texture names.
        // External-memory storage requires new names, so existing FBOs must follow them.
        int maximum =
                org.lwjgl.opengl.GL11C.glGetInteger(
                        org.lwjgl.opengl.GL30C.GL_MAX_COLOR_ATTACHMENTS);
        for (var framebuffer : ownedFramebuffers) {
            for (int attachment = 0; attachment < maximum; attachment++) {
                var replacement = replacements.get(framebuffer.getColorAttachment(attachment));
                if (replacement != null) framebuffer.addColorAttachment(attachment, replacement);
            }
        }
    }
}
