package me.cortex.vulkanite.client.rendering.reconstruction;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class ReconstructionMath {
    private ReconstructionMath() {}

    /** Smooth FOV changes and view bobbing are represented by the previous/current clip matrices. */
    public static boolean projectionCut(Matrix4fc current, Matrix4fc previous) {
        if (Math.abs(current.m33()-previous.m33())>0.01f) return true;
        float x=Math.abs(current.m00()/previous.m00()), y=Math.abs(current.m11()/previous.m11());
        return !Float.isFinite(x) || !Float.isFinite(y) || x<0.8f || x>1.25f || y<0.8f || y>1.25f;
    }

    /** Iris exposes conventional OpenGL [-1,1] depth, even on Minecraft's reverse-Z backend. */
    public static Matrix4f reverseZeroToOne(Matrix4fc irisProjection) {
        var p = new Matrix4f(irisProjection);
        p.m02((p.m03() - p.m02()) * 0.5f);
        p.m12((p.m13() - p.m12()) * 0.5f);
        p.m22((p.m23() - p.m22()) * 0.5f);
        p.m32((p.m33() - p.m32()) * 0.5f);
        return p;
    }
}

