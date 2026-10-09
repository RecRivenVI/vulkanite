package me.cortex.vulkanite.client.rendering.interop;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.util.List;

/** The vertex bindings used to construct the actual backend VAO. */
public interface GlRenderPipelineLayoutAccess {
    List<BackendRenderPipeline.CreateInfo.VertexBuffer> vulkanite$vertexBuffers();

    List<BackendRenderPipeline.CreateInfo.AttribBinding> vulkanite$attribBindings();
}
