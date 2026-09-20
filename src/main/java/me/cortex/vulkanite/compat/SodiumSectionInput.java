package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.acceleration.SectionMeshUpdate;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;

/** Borrows Sodium buffers only while copying its completed mesh into our terrain ABI. */
public final class SodiumSectionInput {
    private SodiumSectionInput() {}

    public static SectionMeshUpdate copy(ChunkBuildOutput build) {
        var metadata = (IAccelerationBuildResult) build;
        var geometry = metadata.getAccelerationGeometryData();
        if (geometry == null) return null; // The build was not captured for this pack generation.

        List<ByteBuffer> owned = new ArrayList<>(geometry.size());
        List<TerrainRenderPass> passes = new ArrayList<>(geometry.size());
        try {
            for (var entry : geometry.entrySet()) {
                var pass = entry.getKey();
                var mesh = build.getMesh(pass);
                if (mesh == null) throw new IllegalStateException("Captured Sodium pass has no mesh");
                var source = mesh.getVertexData().getDirectBuffer();
                var vertexType=metadata.getVertexFormat();
                var vertices = TerrainVertexCompatibility.convert(source, vertexType.getVertexFormat(),
                        vertexType==ChunkMeshFormats.COMPACT);
                owned.add(vertices);
                if (vertices.remaining() != Math.multiplyExact(Math.multiplyExact(entry.getValue().quadCount(), 4),
                        SectionMeshUpdate.VERTEX_STRIDE)) {
                    throw new IllegalStateException("Sodium pass quad count changed before upload");
                }
                passes.add(pass);
                int flags = 0;
                if (!pass.getPipeline().isCull()) flags |= TerrainVertexCompatibility.TWO_SIDED;
                if (pass.supportsFragmentDiscard()) flags |= TerrainVertexCompatibility.ALPHA_DISCARD;
                if (pass.isTranslucent()) flags |= TerrainVertexCompatibility.ALPHA_BLEND;
                TerrainVertexCompatibility.addFlags(vertices, flags);
            }

            var layered=TerrainLayers.compose(owned,passes);
            var original=new ArrayList<>(owned);
            owned.clear();
            for(var mesh:layered) owned.add(mesh.bytes());
            original.forEach(MemoryUtil::memFree);
            List<SectionMeshUpdate.Mesh> meshes = new ArrayList<>(owned.size());
            for (int i = 0; i < owned.size(); i++) {
                var pass = passes.get(i);
                var mesh=layered.get(i);
                int flags = pass == DefaultTerrainRenderPasses.SOLID && !pass.getPipeline().isCull()
                        && !mesh.requiresAnyHit()
                        ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0;
                meshes.add(new SectionMeshUpdate.Mesh(owned.get(i), mesh.canonicalQuadCount(), flags));
            }
            return new SectionMeshUpdate(((SectionHandleAccess) build.section).vulkanite$sectionHandle(),
                    build.submitTime, metadata.vulkanite$generation(), meshes);
        } catch (Throwable failure) {
            owned.forEach(MemoryUtil::memFree);
            throw failure;
        }
    }
}
