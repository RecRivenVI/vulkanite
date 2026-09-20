package me.cortex.vulkanite.mixin.iris;

import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Mixin(value = RenderTargets.class, remap = false)
public abstract class MixinRenderTargets {
    @Shadow @Final private RenderTarget[] targets;
    @Shadow @Final private List<GlFramebuffer> ownedFramebuffers;
    @Unique private int[] vulkanite$oldTextures;

    @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
    private void rememberAttachments(CallbackInfoReturnable<Boolean> cir) {
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
        if (!cir.getReturnValue()) return;
        Map<Integer, Integer> replacements = new HashMap<>();
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != null && vulkanite$oldTextures[i * 2] != 0) {
                replacements.put(vulkanite$oldTextures[i * 2], targets[i].getMainTexture());
                replacements.put(vulkanite$oldTextures[i * 2 + 1], targets[i].getAltTexture());
            }
        }
        // Iris normally resizes mutable storage without changing texture names.
        // External-memory storage requires new names, so existing FBOs must follow them.
        int maximum = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_MAX_COLOR_ATTACHMENTS);
        for (var framebuffer : ownedFramebuffers) {
            for (int attachment = 0; attachment < maximum; attachment++) {
                var replacement = replacements.get(framebuffer.getColorAttachment(attachment));
                if (replacement != null) framebuffer.addColorAttachment(attachment, replacement);
            }
        }
    }
}
