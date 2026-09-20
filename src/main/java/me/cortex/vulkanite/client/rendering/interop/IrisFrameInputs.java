package me.cortex.vulkanite.client.rendering.interop;

import me.cortex.vulkanite.client.rendering.FrameSnapshot;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;

/** Render-thread boundary for copying Iris's unshifted CPU camera inputs into a neutral value. */
public final class IrisFrameInputs {
    private IrisFrameInputs() {}

    /**
     * Captures all host camera inputs synchronously for one Iris world-frame callback.
     * Call once before Vulkanite submits that frame, and
     * discard the result after the frame. CapturedRenderingState exposes no frame token,
     * so the caller's render-thread callback is the same-frame boundary. This performs
     * only CPU copies; it does not wait for or read back GPU state.
     */
    public static FrameSnapshot snapshot() {
        var state = CapturedRenderingState.INSTANCE;
        return new FrameSnapshot(
                state.getGbufferProjection(),
                state.getGbufferModelView(),
                CameraUniforms.getUnshiftedCameraPosition(),
                state.getTickDelta(), SystemTimeUniforms.COUNTER.getAsInt());
    }
}
