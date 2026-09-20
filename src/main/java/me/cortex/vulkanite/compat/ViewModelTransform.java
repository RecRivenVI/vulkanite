package me.cortex.vulkanite.compat;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Converts the HUD's angular projection to actual geometry in the world camera's coordinate frame. */
public final class ViewModelTransform {
    private ViewModelTransform() {}
    public static Matrix4f create(Matrix4fc worldToView, Matrix4fc worldProjection,
                                 float hudFovDegrees, float aspect, Matrix4fc bob) {
        float focal=(float)(1.0/Math.tan(Math.toRadians(hudFovDegrees)*0.5));
        return new Matrix4f(worldToView).invert()
                .scale(focal/aspect/worldProjection.m00(),focal/worldProjection.m11(),1).mul(bob);
    }
}
