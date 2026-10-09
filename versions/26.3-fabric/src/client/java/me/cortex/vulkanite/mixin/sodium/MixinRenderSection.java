package me.cortex.vulkanite.mixin.sodium;

import me.cortex.vulkanite.acceleration.SectionHandle;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.SectionHandleAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSection.class, remap = false)
public class MixinRenderSection implements SectionHandleAccess {
    @Unique private SectionHandle vulkanite$handle;

    @Override
    public SectionHandle vulkanite$sectionHandle() {
        if (vulkanite$handle == null) {
            var section = (RenderSection) (Object) this;
            vulkanite$handle =
                    new SectionHandle(
                            section.getOriginX(), section.getOriginY(), section.getOriginZ());
        }
        return vulkanite$handle;
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void onSectionDelete(CallbackInfo ci) {
        var handle = vulkanite$sectionHandle();
        handle.dispose();
        var runtime = Vulkanite.getIfInitialized();
        if (runtime != null) runtime.sectionRemove(handle);
    }
}
