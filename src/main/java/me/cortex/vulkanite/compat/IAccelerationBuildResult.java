package me.cortex.vulkanite.compat;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;

import java.util.Map;

public interface IAccelerationBuildResult {
    void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map);
    Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData();
    ChunkVertexType getVertexFormat();
    void setVertexFormat(ChunkVertexType format);
}
