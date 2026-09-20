package me.cortex.vulkanite.compat;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

import java.util.HashMap;
import java.util.Map;

// This metadata never owns Sodium's vertex buffers. SodiumSectionInput copies
// them synchronously at the upload boundary and owns only the converted copies.
public class SodiumResultAdapter {
    public static boolean hasGeometry(Map<?, GeometryData> geometry) {
        return geometry != null && geometry.values().stream().anyMatch(data -> data.quadCount() > 0);
    }

    public static void compute(ChunkBuildOutput buildResult) {
        var ebr = (IAccelerationBuildResult) buildResult;
        Map<TerrainRenderPass, GeometryData> map = new HashMap<>();
        for (var pass : buildResult.meshes.entrySet()) {
            var vertData = pass.getValue().getVertexData();

            int stride = ebr.getVertexFormat().getVertexFormat().getVertexSize();

            if (vertData.getLength()%stride != 0)
                throw new IllegalStateException("Mismatch length and stride");
            int vertices = vertData.getLength()/stride;
            if (vertices % 4 != 0)
                throw new IllegalStateException("Non multiple 4 vertex count");

            // Sodium may retain an empty render-pass entry when the last block in a
            // section disappears. Do not let that entry make the section look nonempty:
            // the ray-tracing side must remove its previous BLAS immediately.
            if (vertices != 0) map.put(pass.getKey(), new GeometryData(vertices>>2));
        }

        // An empty build removes previously uploaded geometry; null means not captured.
        ebr.setAccelerationGeometryData(map);
    }
}
