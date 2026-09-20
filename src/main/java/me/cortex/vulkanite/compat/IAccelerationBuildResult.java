package me.cortex.vulkanite.compat;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

import java.util.Map;

public interface IAccelerationBuildResult {
    long vulkanite$generation();
    void vulkanite$generation(long generation);
    void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map);
    Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData();
    ChunkVertexType getVertexFormat();
    void setVertexFormat(ChunkVertexType format);
}
