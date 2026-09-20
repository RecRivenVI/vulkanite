package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.interop.EntityRenderIdentity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(EntityRenderState.class)
public abstract class MixinEntityRenderState implements EntityRenderIdentity {
    @Unique private UUID vulkanite$entityUuid;
    @Unique private boolean vulkanite$cameraBody;

    @Override public UUID vulkanite$entityUuid() { return vulkanite$entityUuid; }
    @Override public void vulkanite$entityUuid(UUID uuid) { vulkanite$entityUuid = uuid; }
    @Override public boolean vulkanite$cameraBody() { return vulkanite$cameraBody; }
    @Override public void vulkanite$cameraBody(boolean value) { vulkanite$cameraBody = value; }
}
