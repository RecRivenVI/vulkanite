package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.compat.ActivePack;
import me.cortex.vulkanite.compat.GeometryData;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = ChunkBuildOutput.class, remap = false)
public class MixinChunkBuildResult implements IAccelerationBuildResult {
    @Unique private long vulkanite$generation;
    public long vulkanite$generation() { return vulkanite$generation; }
    public void vulkanite$generation(long generation) { vulkanite$generation = generation; }
    /** Empty map = Sodium-confirmed empty result; null = not captured (cancel/skip/pack mismatch). */
    @Unique private Map<TerrainRenderPass, GeometryData> geometryMap;
    @Unique private ChunkVertexType vertexType;

    /**
     * Sodium's all-air shortcut builds {@code BuiltSectionInfo.EMPTY} with an empty mesh map
     * on the render thread and never enters {@code ChunkBuilderMeshingTask.execute}, so the
     * meshing capture never runs. Accept that as a confirmed empty result, not as "uncaptured".
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void vulkanite$acceptSodiumEmptyShortcut(CallbackInfo ci) {
        ChunkBuildOutput self = (ChunkBuildOutput) (Object) this;
        if (self.info == BuiltSectionInfo.EMPTY && self.meshes != null && self.meshes.isEmpty()) {
            this.geometryMap = Map.of();
            this.vulkanite$generation = ActivePack.state().generation();
        }
    }

    @Override
    public void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map) {
        this.geometryMap = map;
    }

    @Override
    public Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData() {
        return geometryMap;
    }

    @Override
    public ChunkVertexType getVertexFormat() {
        return vertexType;
    }

    @Override
    public void setVertexFormat(ChunkVertexType format) {
        vertexType = format;
    }
}
