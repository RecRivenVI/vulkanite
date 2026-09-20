package me.cortex.vulkanite.client.rendering;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.opengl.GL45C.*;

/** Display-resolution UI segments. Non-separable operations retain their original ordering. */
public final class UiCompositor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/UI");
    public static final boolean ENABLED = !Boolean.getBoolean("vulkanite.ui.disabled");
    private static final int VERIFY_INTERVAL = Math.max(0, Integer.getInteger("vulkanite.ui.verifyInterval", 0));
    private TextureTarget hudless, layer, reference;
    private GpuFormat depthFormat;
    private int program;
    private long frameId;
    private boolean active, verify, referenceValid;
    private int segments, directSegments;
    private final Set<String> dependencies = new LinkedHashSet<>();
    private String lastStatus = "";
    private boolean dumped;
    private boolean failed;

    public record FrameStatus(long frameId, int width, int height, int uiSegments,
                              int directSegments, boolean singleLayerRepresentable, Set<String> dependencies) {}
    private FrameStatus status = new FrameStatus(0, 0, 0, 0, 0, false, Set.of("no-frame"));
    public FrameStatus status() { return status; }

    public void begin(RenderTarget target, boolean worldFrame) {
        frameId++;
        active = false;
        referenceValid = false;
        segments = directSegments = 0;
        dependencies.clear();
        // No partially valid frame escapes if allocation or a later draw fails.
        status = new FrameStatus(frameId, target.width, target.height, 0, 0, false, Set.of("incomplete"));
        if (!ENABLED || failed || !(target.getColorTexture() instanceof GlTexture)
                || target.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM || !target.hasDepth()) {
            status = new FrameStatus(frameId, target.width, target.height, 0, 0, false,
                    Set.of(failed ? "disabled-after-mismatch" : "bypass"));
            return;
        }
        ensureTargets(target);
        hudless.copyColorFrom(target);
        verify = VERIFY_INTERVAL > 0 && (frameId == 1 || frameId % VERIFY_INTERVAL == 0);
        if (!worldFrame) dependencies.add("no-world");
        active = true;
    }

    /** Only proven premultiplied-over composition is represented as one RGBA layer. */
    public static boolean separable(RenderPipeline pipeline) {
        if (pipeline.getColorTargetStates().size() != 1) return false;
        ColorTargetState target = pipeline.getColorTargetStates().getFirst();
        if (target.writeMask() != ColorTargetState.WRITE_ALL || target.format() != GpuFormat.RGBA8_UNORM) return false;
        return target.blendFunction().filter(blend -> blend.equals(BlendFunction.TRANSLUCENT)
                || blend.equals(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)).isPresent();
    }

    public boolean active() { return active; }
    public RenderTarget reference(RenderTarget main) {
        if (!active || !verify) return null;
        reference.copyColorFrom(main);
        reference.copyDepthFrom(main);
        referenceValid = true;
        return reference;
    }

    public RenderTarget beginLayer(RenderTarget main) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(layer.getColorTexture(), new Vector4f(0.0f));
        layer.copyDepthFrom(main);
        return layer;
    }

    public void composeLayer(RenderTarget main) {
        composite(id(layer), id(main), main.width, main.height);
        main.copyDepthFrom(layer);
        segments++;
    }

    public void direct(RenderPipeline pipeline) {
        directSegments++;
        dependencies.add("blend:" + pipeline.getLocation());
    }

    public void backgroundEffect(String name) {
        if (active) dependencies.add(name);
    }

    public void compare(RenderTarget main) {
        if (!active || !verify || !referenceValid) return;
        int bytes = Math.multiplyExact(Math.multiplyExact(main.width, main.height), 4);
        ByteBuffer actual = MemoryUtil.memAlloc(bytes), expected = MemoryUtil.memAlloc(bytes);
        int packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        int packRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int packSkipRows = glGetInteger(GL_PACK_SKIP_ROWS), packSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glMemoryBarrier(GL_ALL_BARRIER_BITS);
            glGetTextureImage(id(main), 0, GL_RGBA, GL_UNSIGNED_BYTE, actual);
            glGetTextureImage(id(reference), 0, GL_RGBA, GL_UNSIGNED_BYTE, expected);
            long total = 0, outsideTolerance = 0;
            int max = 0;
            for (int pixel = 0; pixel < bytes; pixel += 4) {
                int pixelMax = 0;
                for (int channel = 0; channel < 3; channel++) {
                    int delta = Math.abs(Byte.toUnsignedInt(actual.get(pixel + channel)) - Byte.toUnsignedInt(expected.get(pixel + channel)));
                    total += delta;
                    pixelMax = Math.max(pixelMax, delta);
                }
                max = Math.max(max, pixelMax);
                if (pixelMax > 2) outsideTolerance++;
            }
            LOGGER.info("UI compare frame={} size={}x{} maxRgb8={} meanRgb8={} pixelsOver2={} segments={} direct={} dependencies={}",
                    frameId, main.width, main.height, max, total / (main.width * (double)main.height * 3),
                    outsideTolerance, segments, directSegments, dependencies);
            if (outsideTolerance != 0) {
                if (!dumped && System.getProperty("vulkanite.ui.dumpDirectory") != null) {
                    dumped = true;
                    dump(actual, main.width, main.height, "actual");
                    dump(expected, main.width, main.height, "reference");
                    glGetTextureImage(id(layer), 0, GL_RGBA, GL_UNSIGNED_BYTE, actual);
                    dump(actual, main.width, main.height, "ui");
                    glGetTextureImage(id(hudless), 0, GL_RGBA, GL_UNSIGNED_BYTE, actual);
                    dump(actual, main.width, main.height, "hudless");
                }
                // Preserve the reference image on a sampled failure; never label it FG-ready.
                main.copyColorFrom(reference);
                main.copyDepthFrom(reference);
                dependencies.add("recomposition-mismatch");
                failed = true;
                LOGGER.error("UI recomposition disabled for this session after reference mismatch");
            }
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
            glPixelStorei(GL_PACK_ALIGNMENT, packAlignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, packRowLength);
            glPixelStorei(GL_PACK_SKIP_ROWS, packSkipRows);
            glPixelStorei(GL_PACK_SKIP_PIXELS, packSkipPixels);
            MemoryUtil.memFree(actual);
            MemoryUtil.memFree(expected);
            referenceValid = false;
        }
    }

    private void dump(ByteBuffer pixels, int width, int height, String name) {
        try {
            var directory = java.nio.file.Path.of(System.getProperty("vulkanite.ui.dumpDirectory"));
            java.nio.file.Files.createDirectories(directory);
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int rgba = Byte.toUnsignedInt(pixels.get(i + 3)) << 24 | Byte.toUnsignedInt(pixels.get(i)) << 16
                        | Byte.toUnsignedInt(pixels.get(i + 1)) << 8 | Byte.toUnsignedInt(pixels.get(i + 2));
                image.setRGB(x, height - 1 - y, rgba);
            }
            javax.imageio.ImageIO.write(image, "png", directory.resolve("frame-" + frameId + "-" + name + ".png").toFile());
        } catch (java.io.IOException exception) { LOGGER.warn("Cannot export UI comparison", exception); }
    }

    public void end(RenderTarget main, boolean completed) {
        if (!active) return;
        if (!completed) dependencies.add("incomplete");
        // Segment-local images are not a stable FG resource set. This flag only describes
        // the 2D GUI algebra, not readiness of 3D HUD, motion inputs or presentation.
        status = new FrameStatus(frameId, main.width, main.height, segments, directSegments,
                completed && dependencies.isEmpty() && segments <= 1, Set.copyOf(dependencies));
        String summary = "segments=" + segments + " direct=" + directSegments + " dependencies=" + dependencies;
        if (!summary.equals(lastStatus)) {
            LOGGER.info("UI composition {}", summary);
            lastStatus = summary;
        }
        active = false;
    }

    private void ensureTargets(RenderTarget main) {
        GpuFormat depth = main.getDepthTexture().getFormat();
        if (layer != null && layer.width == main.width && layer.height == main.height && depth == depthFormat) return;
        closeTargets();
        depthFormat = depth;
        hudless = new TextureTarget("Vulkanite pre-GUI", main.width, main.height, GpuFormat.RGBA8_UNORM, null);
        layer = new TextureTarget("Vulkanite UI layer", main.width, main.height, GpuFormat.RGBA8_UNORM, depth);
        if (VERIFY_INTERVAL > 0) reference = new TextureTarget("Vulkanite UI reference", main.width, main.height, GpuFormat.RGBA8_UNORM, depth);
    }

    private static int id(RenderTarget target) { return ((GlTexture)target.getColorTexture()).glId(); }

    private void composite(int ui, int destination, int width, int height) {
        if (program == 0) {
            int shader = glCreateShader(GL_COMPUTE_SHADER);
            glShaderSource(shader, """
                    #version 430 core
                    layout(local_size_x=16, local_size_y=16) in;
                    layout(binding=0, rgba8) readonly uniform image2D uiLayer;
                    layout(binding=1, rgba8) uniform image2D outputColor;
                    void main() {
                        ivec2 p=ivec2(gl_GlobalInvocationID.xy);
                        if(any(greaterThanEqual(p,imageSize(outputColor)))) return;
                        vec4 ui=imageLoad(uiLayer,p), dst=imageLoad(outputColor,p);
                        imageStore(outputColor,p,ui + (1.0-ui.a)*dst);
                    }
                    """);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                String error = glGetShaderInfoLog(shader);
                glDeleteShader(shader);
                throw new IllegalStateException(error);
            }
            program = glCreateProgram();
            glAttachShader(program, shader);
            glLinkProgram(program);
            glDeleteShader(shader);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                String error = glGetProgramInfoLog(program);
                glDeleteProgram(program); program = 0;
                throw new IllegalStateException(error);
            }
        }
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int[][] bindings = new int[2][6];
        int[] keys = {GL_IMAGE_BINDING_NAME, GL_IMAGE_BINDING_LEVEL, GL_IMAGE_BINDING_LAYERED,
                GL_IMAGE_BINDING_LAYER, GL_IMAGE_BINDING_ACCESS, GL_IMAGE_BINDING_FORMAT};
        for (int unit = 0; unit < 2; unit++) for (int key = 0; key < keys.length; key++) bindings[unit][key] = glGetIntegeri(keys[key], unit);
        try {
            glMemoryBarrier(GL_ALL_BARRIER_BITS);
            glUseProgram(program);
            glBindImageTexture(0, ui, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
            glBindImageTexture(1, destination, 0, false, 0, GL_READ_WRITE, GL_RGBA8);
            glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
            glMemoryBarrier(GL_ALL_BARRIER_BITS);
        } finally {
            for (int unit = 0; unit < 2; unit++) {
                int[] b = bindings[unit];
                glBindImageTexture(unit, b[0], b[1], b[2] != 0, b[3], b[4], b[5]);
            }
            glUseProgram(previousProgram);
        }
    }

    private void closeTargets() {
        if (hudless != null) hudless.destroyBuffers();
        if (layer != null) layer.destroyBuffers();
        if (reference != null) reference.destroyBuffers();
        hudless = layer = reference = null;
    }

    @Override public void close() {
        active = false;
        closeTargets();
        if (program != 0) glDeleteProgram(program);
        program = 0;
    }
}
