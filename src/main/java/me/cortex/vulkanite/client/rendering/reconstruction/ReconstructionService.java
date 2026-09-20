package me.cortex.vulkanite.client.rendering.reconstruction;

import me.cortex.vulkanite.client.rendering.reconstruction.backend.NgxReconstructionBackend;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * Renderer-level SR/RR service. Input production is selected independently
 * from the reconstruction backend so future Iris/OpenGL inputs can reuse NGX.
 */
public final class ReconstructionService implements AutoCloseable {
    private final ReconstructionInputProvider inputProvider;
    private final NgxReconstructionBackend backend;

    public ReconstructionService(VContext ctx, ReconstructionInputProvider inputProvider) {
        this.inputProvider = inputProvider;
        this.backend = new NgxReconstructionBackend(ctx, inputProvider.request());
    }

    public ReconstructionRequest request() { return inputProvider.request(); }
    public String inputProviderName() { return inputProvider.name(); }
    public int width() { return backend.width(); }
    public int height() { return backend.height(); }
    public boolean enabled() { return backend.enabled(); }
    public void prepare(int width, int height) { backend.prepare(width, height); }
    public void readExposure(VGImage meter) { backend.readExposure(meter); }
    public void writeCamera(ByteBuffer buffer, Matrix4f view, Matrix4f projection) { backend.writeCamera(buffer, view, projection); }
    public void bindInputs(DescriptorUpdateBuilder update) { inputProvider.bind(this, update); }
    public void bindRayTracingInputs(DescriptorUpdateBuilder update) { backend.bind(update); }
    public void execute(VCmdBuff cmd, VImage target) { backend.execute(cmd, target); }
    @Override public void close() { backend.close(); }
}
