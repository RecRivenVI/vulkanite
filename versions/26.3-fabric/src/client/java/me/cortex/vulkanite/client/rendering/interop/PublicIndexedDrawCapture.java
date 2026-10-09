package me.cortex.vulkanite.client.rendering.interop;

import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_VERTEX_ATTRIB;
import static org.lwjgl.opengl.GL20C.glGetAttribLocation;
import static org.lwjgl.opengl.GL20C.glGetVertexAttribfv;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL30C.glGetVertexAttribIiv;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45C.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.cortex.vulkanite.compat.EntityFrame;
import me.cortex.vulkanite.compat.IrisEntityVertexIds;
import me.cortex.vulkanite.mixin.minecraft.GlRenderPassPipelineAccess;
import me.cortex.vulkanite.mixin.minecraft.GlRenderPipelineSurfaceAccess;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryStack;

/** Render-thread copies of public indexed draws; no producer buffer crosses the frame boundary. */
public final class PublicIndexedDrawCapture {
    private static final long MAX_BUFFER_BYTES = 256L << 20;
    private static final long MAX_FRAME_BYTES = 512L << 20;
    private static final ThreadLocal<Building> CURRENT = new ThreadLocal<>();
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Vulkanite/PublicDraw");
    private static final Set<Integer> REPORTED_UPSTREAM_GL_ERRORS = ConcurrentHashMap.newKeySet();

    public record Layout(
            int stride,
            int position,
            int uv,
            int color,
            int normal,
            int irisEntity,
            int uv1,
            int uv2,
            int midUv,
            int tangent,
            int mcEntity,
            int midBlock) {
        public Layout(int stride, int position, int uv, int color, int normal, int irisEntity) {
            this(stride, position, uv, color, normal, irisEntity, -1, -1, -1, -1, -1, -1);
        }
    }

    public record Constants(byte[] bytes, int presence) {
        public Constants {
            bytes = bytes.clone();
        }
    }

    public record Draw(
            byte[] vertexBytes,
            byte[] indexBytes,
            byte[] transformBytes,
            Layout layout,
            int indexBytesPerElement,
            int indexCount,
            int baseVertex,
            Constants constants,
            EntityFrame.MaterialTextures textures,
            boolean twoSided,
            boolean alphaBlend,
            ProducedDraws.Pass pass,
            Matrix4f passView,
            Vector3d cameraOrigin,
            int sourceVertexHandle,
            int sourceIndexHandle,
            int firstIndex) {
        public Draw {
            passView = new Matrix4f(passView);
            cameraOrigin = new Vector3d(cameraOrigin);
        }

        public ByteBuffer vertices() {
            return ByteBuffer.wrap(vertexBytes).order(ByteOrder.nativeOrder());
        }

        public ByteBuffer indices() {
            return ByteBuffer.wrap(indexBytes).order(ByteOrder.nativeOrder());
        }

        public ByteBuffer transform() {
            return ByteBuffer.wrap(transformBytes).order(ByteOrder.nativeOrder());
        }
    }

    private static final class Snapshot {
        final int glId;
        final int size;
        boolean freed;

        Snapshot(GlBuffer source, long offset, long length, Building owner) {
            if (length <= 0 || length > MAX_BUFFER_BYTES || length > Integer.MAX_VALUE)
                throw new IllegalStateException(
                        "Indexed draw snapshot exceeds the supported buffer size: " + length);
            if (offset < 0 || offset > source.size() || length > source.size() - offset)
                throw new IllegalStateException(
                        "Indexed draw snapshot exceeds its producer buffer");
            if (owner.bytes > MAX_FRAME_BYTES - length)
                throw new IllegalStateException(
                        "Indexed draw frame snapshot exceeds " + MAX_FRAME_BYTES + " bytes");
            clearPriorGlErrors();
            int created = glCreateBuffers();
            try {
                checkOwnGlError("create indexed draw snapshot");
                if (created == 0)
                    throw new IllegalStateException(
                            "OpenGL returned no indexed draw snapshot buffer");
                glNamedBufferStorage(created, length, GL_MAP_READ_BIT | GL_DYNAMIC_STORAGE_BIT);
                checkOwnGlError("allocate indexed draw snapshot");
                glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                checkOwnGlError("barrier before indexed draw snapshot");
                glCopyNamedBufferSubData(source.handle(), created, offset, 0, length);
                checkOwnGlError("copy indexed draw snapshot");
            } catch (Throwable failure) {
                if (created != 0)
                    try {
                        glDeleteBuffers(created);
                    } catch (Throwable cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                throw failure;
            }
            glId = created;
            size = Math.toIntExact(length);
            owner.bytes += length;
            owner.snapshots.add(this);
        }

        byte[] read() {
            ByteBuffer mapped = glMapNamedBufferRange(glId, 0, size, GL_MAP_READ_BIT);
            if (mapped == null)
                throw new IllegalStateException("Could not map owned indexed draw snapshot");
            try {
                byte[] copy = new byte[size];
                mapped.get(copy);
                return copy;
            } finally {
                if (!glUnmapNamedBuffer(glId))
                    throw new IllegalStateException(
                            "Owned indexed draw snapshot was corrupted while mapped");
            }
        }

        void free() {
            if (!freed) {
                freed = true;
                glDeleteBuffers(glId);
            }
        }
    }

    private static void clearPriorGlErrors() {
        for (int count = 0; count < 16; count++) {
            int error = glGetError();
            if (error == GL_NO_ERROR) return;
            if (error == GL_CONTEXT_LOST)
                throw new IllegalStateException(
                        "OpenGL context was lost before indexed draw capture");
            if (REPORTED_UPSTREAM_GL_ERRORS.add(error))
                LOGGER.warn(
                        "Existing producer OpenGL error before public draw capture: 0x{}",
                        Integer.toHexString(error));
        }
        throw new IllegalStateException(
                "Existing OpenGL error stream did not drain before indexed draw capture");
    }

    private static void checkOwnGlError(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR)
            throw new IllegalStateException(
                    "OpenGL failed to " + operation + ": 0x" + Integer.toHexString(error));
    }

    private record Pending(
            Snapshot vertices,
            Snapshot indices,
            Snapshot transform,
            Layout layout,
            int indexBytes,
            int indexCount,
            int baseVertex,
            Constants constants,
            EntityFrame.MaterialTextures textures,
            boolean twoSided,
            boolean alphaBlend,
            ProducedDraws.Pass pass,
            Matrix4f passView,
            Vector3d cameraOrigin,
            int sourceVertexHandle,
            int sourceIndexHandle,
            int firstIndex) {}

    private static final class Building {
        final List<Snapshot> snapshots = new ArrayList<>();
        final List<Pending> draws = new ArrayList<>();
        final IdentityHashMap<GpuBuffer, Snapshot> vertexCopies = new IdentityHashMap<>();
        final IdentityHashMap<GpuBuffer, Boolean> writableMappedBuffers = new IdentityHashMap<>();
        boolean inBatch;
        long bytes;
        Throwable failure;

        void fail(Throwable error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }

        void close() {
            inBatch = false;
            for (Snapshot snapshot : snapshots) snapshot.free();
            snapshots.clear();
        }
    }

    private PublicIndexedDrawCapture() {}

    public static void begin() {
        Building previous = CURRENT.get();
        if (previous != null) previous.close();
        CURRENT.set(new Building());
    }

    public static void beginBatch() {
        Building building = CURRENT.get();
        if (building != null) building.inBatch = true;
    }

    public static void endBatch() {
        Building building = CURRENT.get();
        if (building != null) building.inBatch = false;
    }

    /** A later draw must not reuse a copy from before a normal RenderPearl buffer write. */
    public static void bufferWritten(GpuBuffer buffer, boolean writableMapping) {
        Building building = CURRENT.get();
        if (building == null) return;
        building.vertexCopies.remove(buffer);
        if (writableMapping) building.writableMappedBuffers.put(buffer, Boolean.TRUE);
    }

    public static void fail(Throwable error) {
        Building building = CURRENT.get();
        if (building != null) building.fail(error);
    }

    /** Called after RenderPearl has applied this Draw's actual uniform uploader. */
    public static void observe(
            RenderPass.Draw<?> draw,
            GpuBuffer indexBuffer,
            IndexType indexType,
            RenderPassBackend backend,
            FrontendRenderPipeline frontend,
            Map<String, Object> uniforms) {
        Building building = CURRENT.get();
        if (building == null || building.failure != null || HandRenderer.INSTANCE.isActive())
            return;
        try {
            if (!building.inBatch)
                throw new IllegalStateException("Indexed draw has no capture batch");
            Object transformValue = uniforms.get("DynamicTransforms");
            Object textureValue = uniforms.get("Sampler0");
            if (!(transformValue instanceof GpuBufferSlice transform)
                    || !(textureValue instanceof TextureViewAndSampler texture)) return;
            if (!(backend instanceof GlRenderPassPipelineAccess actual))
                throw new IllegalStateException(
                        "Indexed draw is not using the OpenGL RenderPearl backend");
            GlRenderPipeline pipeline = actual.vulkanite$pipeline();
            if (pipeline == null)
                throw new IllegalStateException("Indexed draw has no bound backend pipeline");
            if (pipeline.primitiveTopology() != 4 || draw.indexCount() == 0) return; // GL_TRIANGLES
            VertexFormat sourceFormat =
                    frontend != null
                                    && draw.slot() >= 0
                                    && draw.slot() < frontend.vertexFormats().size()
                            ? frontend.vertexFormats().get(draw.slot())
                            : null;
            Layout layout = layout(pipeline, draw.slot(), sourceFormat);
            Constants constants = constants(pipeline, layout);
            if (!(draw.vertexBuffer() instanceof GlBuffer sourceVertices)
                    || !(indexBuffer instanceof GlBuffer sourceIndices)
                    || !(transform.buffer() instanceof GlBuffer sourceTransform))
                throw new IllegalStateException("Indexed draw buffers are not OpenGL buffers");
            if (indexType == null || draw.indexCount() < 0 || draw.indexCount() % 3 != 0)
                throw new IllegalStateException("Indexed triangle draw has an invalid index range");
            long indexStart = Math.multiplyExact((long) draw.firstIndex(), indexType.bytes);
            long indexLength = Math.multiplyExact((long) draw.indexCount(), indexType.bytes);
            if (transform.length() < 156)
                throw new IllegalStateException(
                        "DynamicTransforms draw uniform is shorter than its standard fields");
            boolean writableMapped =
                    building.writableMappedBuffers.containsKey(draw.vertexBuffer());
            Snapshot vertexCopy =
                    writableMapped ? null : building.vertexCopies.get(draw.vertexBuffer());
            if (vertexCopy == null) {
                vertexCopy = new Snapshot(sourceVertices, 0, sourceVertices.size(), building);
                if (!writableMapped) building.vertexCopies.put(draw.vertexBuffer(), vertexCopy);
            }
            Snapshot indexCopy = new Snapshot(sourceIndices, indexStart, indexLength, building);
            Snapshot transformCopy =
                    new Snapshot(sourceTransform, transform.offset(), 156, building);
            var sampled = TextureInputs.materialTextures(texture.view(), texture.sampler());
            var surface = (GlRenderPipelineSurfaceAccess) (Object) pipeline;
            boolean twoSided = !surface.vulkanite$cull();
            boolean alphaBlend = false;
            for (boolean enabled : surface.vulkanite$blendEnabled()) alphaBlend |= enabled;
            ProducedDraws.Pass pass =
                    ShadowRenderer.ACTIVE ? ProducedDraws.Pass.SHADOW : ProducedDraws.Pass.MAIN;
            Matrix4f passView =
                    ShadowRenderer.ACTIVE
                            ? new Matrix4f(ShadowRenderer.MODELVIEW)
                            : new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
            Vector3d cameraOrigin = new Vector3d(CameraUniforms.getUnshiftedCameraPosition());
            if (!passView.isFinite() || !cameraOrigin.isFinite())
                throw new IllegalStateException("Indexed draw pass camera is not finite");
            building.draws.add(
                    new Pending(
                            vertexCopy,
                            indexCopy,
                            transformCopy,
                            layout,
                            indexType.bytes,
                            draw.indexCount(),
                            draw.baseVertex(),
                            constants,
                            sampled,
                            twoSided,
                            alphaBlend,
                            pass,
                            passView,
                            cameraOrigin,
                            sourceVertices.handle(),
                            sourceIndices.handle(),
                            draw.firstIndex()));
        } catch (Throwable error) {
            building.fail(error);
        }
    }

    private static Layout layout(GlRenderPipeline pipeline, int slot, VertexFormat sourceFormat) {
        var actual = (GlRenderPipelineLayoutAccess) (Object) pipeline;
        BackendRenderPipeline.CreateInfo.VertexBuffer buffer = null;
        for (var candidate : actual.vulkanite$vertexBuffers()) {
            if (candidate.bufferSlot() != slot) continue;
            if (buffer != null)
                throw new IllegalArgumentException("Indexed vertex slot has ambiguous bindings");
            buffer = candidate;
        }
        if (buffer == null || buffer.stride() <= 0 || buffer.stepRate() != 0)
            throw new IllegalArgumentException(
                    "Indexed draw has no per-vertex backend binding for slot " + slot);
        int activePosition =
                attributeOffset(pipeline, actual, slot, "Position", GpuFormat.RGB32_FLOAT);
        boolean trustedSource =
                sourceFormat != null
                        && sourceFormat.getVertexSize() == buffer.stride()
                        && activePosition >= 0
                        && sourceElementOffset(sourceFormat, "Position", GpuFormat.RGB32_FLOAT)
                                == activePosition;
        int position =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "Position",
                        GpuFormat.RGB32_FLOAT);
        int uv =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "UV0",
                        GpuFormat.RG32_FLOAT);
        int color =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "Color",
                        GpuFormat.RGBA8_UNORM);
        int normal =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "Normal",
                        GpuFormat.RGBA8_SNORM);
        int irisEntity =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        IrisEntityVertexIds.ATTRIBUTE_NAME,
                        GpuFormat.RGBA16_UINT);
        int uv1 =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "UV1",
                        GpuFormat.RG16_SINT);
        int uv2 =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "UV2",
                        GpuFormat.RG16_SINT);
        int midUv =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "mc_midTexCoord",
                        GpuFormat.RG32_FLOAT);
        int tangent =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "at_tangent",
                        GpuFormat.RGBA8_SNORM);
        int mcEntity =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "mc_Entity",
                        GpuFormat.RG16_SINT);
        int midBlock =
                resolvedOffset(
                        pipeline,
                        actual,
                        slot,
                        sourceFormat,
                        trustedSource,
                        "at_midBlock",
                        GpuFormat.RGBA8_SNORM);
        if (position < 0 || uv < 0)
            throw new IllegalArgumentException("Indexed triangle draw lacks Position or UV0");
        if (position + 12 > buffer.stride()
                || uv + 8 > buffer.stride()
                || color >= 0 && color + 4 > buffer.stride()
                || normal >= 0 && normal + 4 > buffer.stride()
                || irisEntity >= 0 && irisEntity + 8 > buffer.stride()
                || uv1 >= 0 && uv1 + 4 > buffer.stride()
                || uv2 >= 0 && uv2 + 4 > buffer.stride()
                || midUv >= 0 && midUv + 8 > buffer.stride()
                || tangent >= 0 && tangent + 4 > buffer.stride()
                || mcEntity >= 0 && mcEntity + 4 > buffer.stride()
                || midBlock >= 0 && midBlock + 4 > buffer.stride())
            throw new IllegalArgumentException(
                    "Indexed vertex attribute exceeds the backend stride");
        return new Layout(
                buffer.stride(),
                position,
                uv,
                color,
                normal,
                irisEntity,
                uv1,
                uv2,
                midUv,
                tangent,
                mcEntity,
                midBlock);
    }

    private static int resolvedOffset(
            GlRenderPipeline pipeline,
            GlRenderPipelineLayoutAccess actual,
            int slot,
            VertexFormat sourceFormat,
            boolean trustedSource,
            String name,
            GpuFormat expected) {
        int active = attributeOffset(pipeline, actual, slot, name, expected);
        if (active >= 0) {
            if (trustedSource) {
                int advertised = sourceElementOffset(sourceFormat, name, expected);
                if (advertised >= 0 && advertised != active)
                    throw new IllegalArgumentException(
                            "Source and actual backend disagree on " + name);
            }
            return active;
        }
        // An active but unbound attribute is a GL constant, not a VBO field.
        if (attributeLocation(pipeline, name) >= 0 || !trustedSource) return -1;
        return sourceElementOffset(sourceFormat, name, expected);
    }

    /** Prove the source element occupies bytes, excluding Iris-style zero-stride dummy entries. */
    private static int sourceElementOffset(VertexFormat format, String name, GpuFormat expected) {
        var elements = format.getElements();
        int found = -1;
        for (int i = 0; i < elements.size(); i++) {
            var element = elements.get(i);
            if (!element.name().equals(name)) continue;
            if (found >= 0 || element.format() != expected) return -1;
            int end = element.offset() + expected.blockSize();
            if (element.offset() < 0 || end > format.getVertexSize()) return -1;
            if (i + 1 < elements.size() && elements.get(i + 1).offset() < end) return -1;
            found = element.offset();
        }
        return found;
    }

    private static int attributeOffset(
            GlRenderPipeline pipeline,
            GlRenderPipelineLayoutAccess actual,
            int slot,
            String name,
            GpuFormat format) {
        int location = attributeLocation(pipeline, name);
        if (location < 0) return -1;
        for (var binding : actual.vulkanite$attribBindings()) {
            if (binding.location() != location) continue;
            if (binding.bufferSlot() != slot)
                throw new IllegalArgumentException(
                        "Indexed " + name + " uses a different vertex slot");
            if (binding.format() != format)
                throw new IllegalArgumentException(
                        "Indexed " + name + " has unsupported backend format " + binding.format());
            return binding.offset();
        }
        return -1; // The enabled program attribute is supplied as a GL constant.
    }

    private static Constants constants(GlRenderPipeline pipeline, Layout layout) {
        byte[] values = new byte[EntityFrame.STRIDE];
        var bytes = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder());
        int mask = 0;
        mask |= layout.color() >= 0 ? EntityFrame.COLOR_PRESENT : 0;
        mask |= layout.normal() >= 0 ? EntityFrame.NORMAL_PRESENT : 0;
        mask |= layout.uv1() >= 0 ? EntityFrame.UV1_PRESENT : 0;
        mask |= layout.uv2() >= 0 ? EntityFrame.UV2_PRESENT : 0;
        mask |= layout.midUv() >= 0 ? EntityFrame.MID_TEXCOORD_PRESENT : 0;
        mask |= layout.tangent() >= 0 ? EntityFrame.TANGENT_PRESENT : 0;
        mask |= layout.irisEntity() >= 0 ? EntityFrame.IRIS_ENTITY_PRESENT : 0;
        mask |= layout.mcEntity() >= 0 ? EntityFrame.MC_ENTITY_PRESENT : 0;
        mask |= layout.midBlock() >= 0 ? EntityFrame.MID_BLOCK_PRESENT : 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int location;
            if (layout.color() < 0 && (location = attributeLocation(pipeline, "Color")) >= 0) {
                bytes.putInt(12, packedFloat4(stack, location, false));
                mask |= EntityFrame.COLOR_PRESENT | EntityFrame.COLOR_CONSTANT;
            }
            if (layout.normal() < 0 && (location = attributeLocation(pipeline, "Normal")) >= 0) {
                bytes.putInt(24, packedFloat4(stack, location, true));
                mask |= EntityFrame.NORMAL_PRESENT | EntityFrame.NORMAL_CONSTANT;
            }
            if (layout.uv1() < 0 && (location = attributeLocation(pipeline, "UV1")) >= 0) {
                signedPair(stack, location, bytes, 40);
                mask |= EntityFrame.UV1_PRESENT | EntityFrame.UV1_CONSTANT;
            }
            if (layout.uv2() < 0 && (location = attributeLocation(pipeline, "UV2")) >= 0) {
                signedPair(stack, location, bytes, 48);
                mask |= EntityFrame.UV2_PRESENT | EntityFrame.UV2_CONSTANT;
            }
            if (layout.midUv() < 0
                    && (location = attributeLocation(pipeline, "mc_midTexCoord")) >= 0) {
                var read = stack.mallocFloat(4);
                glGetVertexAttribfv(location, GL_CURRENT_VERTEX_ATTRIB, read);
                if (!Float.isFinite(read.get(0)) || !Float.isFinite(read.get(1)))
                    throw new IllegalArgumentException("Public draw mid UV constant is not finite");
                bytes.putFloat(56, read.get(0)).putFloat(60, read.get(1));
                mask |= EntityFrame.MID_TEXCOORD_PRESENT | EntityFrame.MID_TEXCOORD_CONSTANT;
            }
            if (layout.tangent() < 0
                    && (location = attributeLocation(pipeline, "at_tangent")) >= 0) {
                bytes.putInt(64, packedFloat4(stack, location, true));
                mask |= EntityFrame.TANGENT_PRESENT | EntityFrame.TANGENT_CONSTANT;
            }
            if (layout.irisEntity() < 0
                    && (location = attributeLocation(pipeline, IrisEntityVertexIds.ATTRIBUTE_NAME))
                            >= 0) {
                var read = stack.mallocInt(4);
                glGetVertexAttribIiv(location, GL_CURRENT_VERTEX_ATTRIB, read);
                for (int i = 0; i < 4; i++) bytes.putInt(68 + i * 4, read.get(i));
                mask |= EntityFrame.IRIS_ENTITY_PRESENT | EntityFrame.IRIS_ENTITY_CONSTANT;
            }
            if (layout.mcEntity() < 0
                    && (location = attributeLocation(pipeline, "mc_Entity")) >= 0) {
                signedPair(stack, location, bytes, 84);
                mask |= EntityFrame.MC_ENTITY_PRESENT | EntityFrame.MC_ENTITY_CONSTANT;
            }
            if (layout.midBlock() < 0
                    && (location = attributeLocation(pipeline, "at_midBlock")) >= 0) {
                bytes.putInt(92, packedFloat4(stack, location, true));
                mask |= EntityFrame.MID_BLOCK_PRESENT | EntityFrame.MID_BLOCK_CONSTANT;
            }
        }
        return new Constants(values, mask);
    }

    private static void signedPair(MemoryStack stack, int location, ByteBuffer target, int offset) {
        var read = stack.mallocInt(4);
        glGetVertexAttribIiv(location, GL_CURRENT_VERTEX_ATTRIB, read);
        target.putInt(offset, read.get(0)).putInt(offset + 4, read.get(1));
    }

    private static int packedFloat4(MemoryStack stack, int location, boolean signed) {
        var read = stack.mallocFloat(4);
        glGetVertexAttribfv(location, GL_CURRENT_VERTEX_ATTRIB, read);
        int packed = 0;
        for (int i = 0; i < 4; i++) {
            float value = read.get(i);
            if (!Float.isFinite(value))
                throw new IllegalArgumentException("Public draw attribute constant is not finite");
            int component =
                    signed
                            ? Math.round(Math.clamp(value, -1f, 1f) * 127f)
                            : Math.round(Math.clamp(value, 0f, 1f) * 255f);
            packed |= (component & 255) << (i * 8);
        }
        return packed;
    }

    private static int attributeLocation(GlRenderPipeline pipeline, String name) {
        int program = pipeline.program().getProgramId();
        int location = glGetAttribLocation(program, name);
        // Iris-backed public pipelines may prefix otherwise ordinary attributes.
        return location >= 0 ? location : glGetAttribLocation(program, "iris_" + name);
    }

    /** One fence retires all GL copies before the CPU decodes this frame's owned data. */
    public static List<Draw> finish() {
        Building building = CURRENT.get();
        CURRENT.remove();
        if (building == null) return List.of();
        try {
            if (building.failure != null)
                throw new IllegalStateException(
                        "Public indexed draw capture failed after normal GL submission",
                        building.failure);
            if (building.draws.isEmpty()) return List.of();
            long t0 = System.nanoTime();
            long sync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (sync == 0)
                throw new IllegalStateException("Could not fence public indexed draw copies");
            try {
                for (int attempts = 0; ; attempts++) {
                    int status = glClientWaitSync(sync, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                    if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) break;
                    if (status == GL_WAIT_FAILED || attempts >= 9)
                        throw new IllegalStateException(
                                "Public indexed draw copy fence failed or timed out: " + status);
                }
            } finally {
                glDeleteSync(sync);
            }
            long t1 = System.nanoTime();
            me.cortex.vulkanite.audit.Diagnostics.addCpu("indexedFence", t1 - t0);
            var bytes = new IdentityHashMap<Snapshot, byte[]>();
            long readBytes = 0;
            for (Snapshot snapshot : building.snapshots) {
                bytes.put(snapshot, snapshot.read());
                readBytes += snapshot.size;
            }
            var completed = new ArrayList<Draw>(building.draws.size());
            for (Pending draw : building.draws)
                completed.add(
                        new Draw(
                                bytes.get(draw.vertices),
                                bytes.get(draw.indices),
                                bytes.get(draw.transform),
                                draw.layout,
                                draw.indexBytes,
                                draw.indexCount,
                                draw.baseVertex,
                                draw.constants,
                                draw.textures,
                                draw.twoSided,
                                draw.alphaBlend,
                                draw.pass,
                                draw.passView,
                                draw.cameraOrigin,
                                draw.sourceVertexHandle,
                                draw.sourceIndexHandle,
                                draw.firstIndex));
            me.cortex.vulkanite.audit.Diagnostics.addCpu("indexedReadback", System.nanoTime() - t1);
            me.cortex.vulkanite.audit.Diagnostics.addIndexed(completed.size(), readBytes);
            return List.copyOf(completed);
        } finally {
            building.close();
        }
    }

    public static void discard() {
        Building building = CURRENT.get();
        CURRENT.remove();
        if (building != null) building.close();
    }
}
