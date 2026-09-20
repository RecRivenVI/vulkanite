package me.cortex.vulkanite.acceleration;

import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/** Owns copied, canonical terrain vertices until the synchronous upload returns. */
public final class SectionMeshUpdate implements AutoCloseable {
    public static final int VERTEX_STRIDE = 64;

    /** The buffer may append full coplanar layer records after the canonical BLAS vertices. */
    public record Mesh(ByteBuffer vertices, int canonicalQuadCount, int geometryFlags) {}

    private final SectionHandle section;
    private final long revision;
    private final long generation;
    private final List<Mesh> meshes;
    private boolean closed;

    public SectionMeshUpdate(
            SectionHandle section, long revision, long generation, List<Mesh> meshes) {
        this.section = section;
        this.revision = revision;
        this.generation = generation;
        this.meshes = List.copyOf(meshes);
    }

    public SectionHandle section() {
        return section;
    }

    public long revision() {
        return revision;
    }

    public long generation() {
        return generation;
    }

    public List<Mesh> meshes() {
        return meshes;
    }

    public boolean isEmpty() {
        return meshes.stream().noneMatch(mesh -> mesh.canonicalQuadCount() > 0);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        meshes.forEach(mesh -> MemoryUtil.memFree(mesh.vertices()));
    }
}
