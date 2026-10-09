package me.cortex.vulkanite.client.rendering;

import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Immutable CPU copy of the Iris camera inputs consumed by one Vulkanite frame. */
public final class FrameSnapshot {
    private final Matrix4f gbufferProjection;
    private final Matrix4f gbufferModelView;
    private final Vector3d cameraPosition;
    private final float irisTickDelta;
    private final int frameCounter;

    public FrameSnapshot(
            Matrix4fc gbufferProjection,
            Matrix4fc gbufferModelView,
            Vector3dc cameraPosition,
            float irisTickDelta,
            int frameCounter) {
        this.gbufferProjection =
                new Matrix4f(Objects.requireNonNull(gbufferProjection, "gbufferProjection"));
        this.gbufferModelView =
                new Matrix4f(Objects.requireNonNull(gbufferModelView, "gbufferModelView"));
        Vector3d positionCopy =
                new Vector3d(Objects.requireNonNull(cameraPosition, "cameraPosition"));
        if (!Double.isFinite(positionCopy.x)
                || !Double.isFinite(positionCopy.y)
                || !Double.isFinite(positionCopy.z)
                || !Float.isFinite(irisTickDelta)) {
            throw new IllegalArgumentException("Frame snapshot camera inputs must be finite");
        }
        this.cameraPosition = positionCopy;
        this.irisTickDelta = irisTickDelta;
        this.frameCounter = frameCounter;
    }

    /** Returns a fresh mutable copy; mutating it cannot change this snapshot. */
    public Matrix4f gbufferProjection() {
        return new Matrix4f(gbufferProjection);
    }

    /** Returns a fresh mutable copy; mutating it cannot change this snapshot. */
    public Matrix4f gbufferModelView() {
        return new Matrix4f(gbufferModelView);
    }

    /** Returns a fresh mutable copy; mutating it cannot change this snapshot. */
    public Vector3d cameraPosition() {
        return new Vector3d(cameraPosition);
    }

    public float irisTickDelta() {
        return irisTickDelta;
    }

    public int frameCounter() {
        return frameCounter;
    }
}
