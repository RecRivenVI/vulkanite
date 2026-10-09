package me.cortex.vulkanite.client.rendering.interop;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import me.cortex.vulkanite.compat.EntityFrame;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix4f;

/**
 * Same-frame adapter-owned copies of the normal Iris/Minecraft producer's Draws. No Result,
 * BufferBuilder, RenderType, or source address crosses this boundary.
 */
public final class ProducedDraws {
    public enum Pass {
        MAIN,
        SHADOW,
        HAND
    }

    public record Input(
            String renderTypeName,
            EntityFrame.MaterialTextures textures,
            boolean twoSided,
            boolean particle,
            boolean alphaCutout,
            boolean alphaBlend) {}

    public record Draw(
            Pass pass,
            Input input,
            VertexFormat format,
            PrimitiveTopology topology,
            int vertexCount,
            byte[] vertexBytes,
            ProducedSubmitRanges.Summary ranges,
            Matrix4f shadowInverse,
            Matrix4f handProjection) {
        public Draw {
            if (shadowInverse != null) shadowInverse = new Matrix4f(shadowInverse);
            if (handProjection != null) handProjection = new Matrix4f(handProjection);
        }

        public ByteBuffer vertices() {
            return ByteBuffer.wrap(vertexBytes)
                    .asReadOnlyBuffer()
                    .order(java.nio.ByteOrder.nativeOrder());
        }
    }

    public record Frame(List<Draw> draws, Throwable failure) {
        public Frame {
            draws = List.copyOf(draws);
        }
    }

    private static final class Building {
        final IdentityHashMap<Object, Input> inputs = new IdentityHashMap<>();
        final ArrayList<Draw> draws = new ArrayList<>();
        Matrix4f handProjection;
        Throwable failure;

        void fail(Throwable error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
    }

    private static final ThreadLocal<Building> CURRENT = new ThreadLocal<>();

    private ProducedDraws() {}

    /** Called once before Iris's shadow, main, and hand producers for this frame. */
    public static void begin() {
        CURRENT.set(new Building());
    }

    /** Called after all normal producers, before the Vulkan scene is assembled. */
    public static Frame finish() {
        Building building = CURRENT.get();
        CURRENT.remove();
        return building == null
                ? new Frame(List.of(), null)
                : new Frame(building.draws, building.failure);
    }

    public static void discard() {
        CURRENT.remove();
    }

    public static void fail(Throwable error) {
        Building building = CURRENT.get();
        if (building != null) building.fail(error);
    }

    /** Actual P_hand = depthBias * hudProjection * bob, captured before Iris uploads it. */
    public static void handProjection(Matrix4f projection) {
        Building building = CURRENT.get();
        if (building != null) building.handProjection = new Matrix4f(projection);
    }

    public static void registerRenderType(
            Object draw, RenderType type, PreparedRenderType prepared) {
        Building building = CURRENT.get();
        if (building == null) return;
        if (building.inputs.containsKey(draw)) return;
        try {
            EntityFrame.MaterialTextures textures = null;
            for (var texture : prepared.textures())
                if ("Sampler0".equals(texture.name())) {
                    textures =
                            TextureInputs.materialTextures(
                                    texture.textureView(), texture.sampler());
                    break;
                }
            boolean blended =
                    type.pipeline().getColorTargetStates().stream()
                            .anyMatch(target -> target.blendFunction().isPresent());
            building.inputs.put(
                    draw,
                    new Input(
                            prepared.name(),
                            textures,
                            !type.pipeline().isCull(),
                            false,
                            !blended,
                            blended));
        } catch (Throwable error) {
            building.fail(error);
        }
    }

    public static void registerParticle(
            Object draw, SingleQuadParticle.Layer layer, AbstractTexture texture) {
        Building building = CURRENT.get();
        if (building == null) return;
        try {
            var textures =
                    TextureInputs.materialTextures(texture.getTextureView(), texture.getSampler());
            building.inputs.put(
                    draw,
                    new Input(
                            "particle:" + layer.textureAtlasLocation(),
                            textures,
                            true,
                            true,
                            !layer.translucent(),
                            layer.translucent()));
        } catch (Throwable error) {
            building.fail(error);
        }
    }

    /** Copies CPU vertices before Draw.append retains a Result that Iris later closes. */
    public static void observe(Object draw, MeshData mesh, ProducedSubmitRanges.Summary ranges) {
        Building building = CURRENT.get();
        if (building == null) return;
        try {
            var state = mesh.drawState();
            Input input = building.inputs.get(draw);
            if (input == null)
                throw new IllegalStateException(
                        "Normal producer Draw has no texture/source mapping: " + state.format());
            int bytes = Math.multiplyExact(state.vertexCount(), state.format().getVertexSize());
            ByteBuffer source = mesh.vertexBuffer().duplicate();
            if (source.remaining() != bytes)
                throw new IllegalStateException(
                        "Normal producer Draw vertex bytes="
                                + source.remaining()
                                + " but format requires "
                                + bytes);
            byte[] copy = new byte[bytes];
            source.get(copy);
            Pass pass =
                    ShadowRenderer.ACTIVE
                            ? Pass.SHADOW
                            : HandRenderer.INSTANCE.isActive() ? Pass.HAND : Pass.MAIN;
            Matrix4f inverse = null;
            if (pass == Pass.SHADOW) {
                if (ShadowRenderer.MODELVIEW == null)
                    throw new IllegalStateException(
                            "Iris shadow Draw has no producer model-view matrix");
                inverse = new Matrix4f(ShadowRenderer.MODELVIEW).invert();
                if (!inverse.isFinite())
                    throw new IllegalStateException(
                            "Iris shadow Draw model-view matrix is non-invertible");
            }
            Matrix4f handProjection = pass == Pass.HAND ? building.handProjection : null;
            if (pass == Pass.HAND && handProjection == null)
                throw new IllegalStateException(
                        "Iris hand Draw has no observed producer projection");
            building.draws.add(
                    new Draw(
                            pass,
                            input,
                            state.format(),
                            state.primitiveTopology(),
                            state.vertexCount(),
                            copy,
                            ranges,
                            inverse,
                            handProjection));
        } catch (Throwable error) {
            building.fail(error);
        }
    }
}
