package me.cortex.vulkanite.mixin.minecraft;
import me.cortex.vulkanite.client.Vulkanite;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public class MixinMinecraftClient {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void onRenderTick(boolean tick, CallbackInfo ci) { Vulkanite.INSTANCE.renderTick(); }
    @Inject(method = "runTick", at = @At("TAIL"))
    private void tickFences(boolean tick, CallbackInfo ci) { Vulkanite.INSTANCE.fenceTick(); }
}
