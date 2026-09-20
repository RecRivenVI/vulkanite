package me.cortex.vulkanite.mixin.minecraft;
import java.util.Queue;
import net.minecraft.client.particle.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ParticleGroup.class)
public interface ParticleGroupAccessor {
    @Accessor("particles") Queue<? extends Particle> vulkanite$particles();
}
