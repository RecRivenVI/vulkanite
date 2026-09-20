package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import me.cortex.vulkanite.client.rendering.interop.EntityRenderIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the normal extract/submit chain to carry stable entity identity to submit nodes. */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderDispatcher {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void vulkanite$rememberExtractedEntity(Entity entity, float delta,
                                                    CallbackInfoReturnable<EntityRenderState> cir) {
        if (cir.getReturnValue() instanceof EntityRenderIdentity state) {
            state.vulkanite$entityUuid(entity.getUUID());
            state.vulkanite$cameraBody(entity == Minecraft.getInstance().getCameraEntity()
                    && Minecraft.getInstance().options.getCameraType().isFirstPerson());
        }
    }

    @WrapMethod(method = "submit")
    private void vulkanite$observeEntitySubmit(EntityRenderState state, CameraRenderState camera,
                                                double x, double y, double z, PoseStack pose,
                                                SubmitNodeCollector nodes, Operation<Void> original) {
        DynamicSubmitOrigins.push((EntityRenderIdentity) state);
        try { original.call(state,camera,x,y,z,pose,nodes); }
        finally { DynamicSubmitOrigins.pop(); }
    }
}
