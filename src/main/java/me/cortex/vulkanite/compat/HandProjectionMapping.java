package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.client.rendering.FrameSnapshot;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Places already-submitted hand vertices in the world while preserving their
 * actual Iris screen projection. Iris's depth compression changes clip z but
 * leaves clip x/y/w intact, so we use its observed x/y/w and restore physical
 * view depth from w. No bob, HUD FOV, or hand submit is recomputed here.
 */
public final class HandProjectionMapping {
    private final Matrix4f handProjection;
    private final Matrix4f inverseWorldProjection;
    private final Matrix4f inverseWorldView;

    public HandProjectionMapping(Matrix4fc actualHandProjection, FrameSnapshot snapshot) {
        if (actualHandProjection == null || snapshot == null)
            throw new IllegalArgumentException("Missing actual Iris hand or world frame projection");
        handProjection = new Matrix4f(actualHandProjection);
        inverseWorldProjection = new Matrix4f(snapshot.gbufferProjection()).invert();
        var camera=snapshot.cameraPosition();
        inverseWorldView = new Matrix4f(snapshot.gbufferModelView())
                .translate((float)-camera.x(),(float)-camera.y(),(float)-camera.z()).invert();
        if (!handProjection.isFinite() || !inverseWorldProjection.isFinite()
                || !inverseWorldView.isFinite())
            throw new IllegalArgumentException("Non-finite Iris hand/world projection");
    }

    public Vector3f position(float x,float y,float z,Vector3f output) {
        viewPosition(x,y,z,output);
        inverseWorldView.transformPosition(output);
        if (!output.isFinite()) throw new IllegalArgumentException("Non-finite projected hand vertex");
        return output;
    }

    private Vector3f viewPosition(float x,float y,float z,Vector3f output) {
        var clip=handProjection.transform(new Vector4f(x,y,z,1));
        float depth=clip.w;
        if (!Float.isFinite(depth) || depth<=0.0001f)
            throw new IllegalArgumentException("Hand vertex is behind the actual Iris projection");
        float ndcX=clip.x/depth,ndcY=clip.y/depth;
        var near=inverseWorldProjection.transformProject(new Vector3f(ndcX,ndcY,0.25f));
        var far=inverseWorldProjection.transformProject(new Vector3f(ndcX,ndcY,0.75f));
        float dz=far.z-near.z;
        if (!Float.isFinite(dz) || Math.abs(dz)<1.0e-7f)
            throw new IllegalArgumentException("Iris world projection has no usable hand ray");
        float t=(-depth-near.z)/dz;
        output.set(near).lerp(far,t);
        if (!output.isFinite()) throw new IllegalArgumentException("Non-finite projected hand view vertex");
        return output;
    }

    public Vector3f normal(float x,float y,float z,float nx,float ny,float nz,
                           Vector3f output) {
        var localToWorld=jacobian(x,y,z);
        new Matrix3f(localToWorld).invert().transpose().transform(output.set(nx,ny,nz));
        if (!output.isFinite() || output.lengthSquared()<1.0e-10f)
            throw new IllegalArgumentException("Degenerate projected hand normal");
        return output.normalize();
    }

    /** Forward Jacobian for tangent directions; return its orientation for tangent.w. */
    public float tangent(float x,float y,float z,float tx,float ty,float tz,
                         Vector3f output) {
        var localToWorld=jacobian(x,y,z);
        localToWorld.transform(output.set(tx,ty,tz));
        if (!output.isFinite() || output.lengthSquared()<1.0e-10f)
            throw new IllegalArgumentException("Degenerate projected hand tangent");
        return Math.signum(localToWorld.determinant());
    }

    private Matrix3f jacobian(float x,float y,float z) {
        // Differentiate in view space before adding a potentially huge camera origin.
        final float step=0.1f;
        var origin=viewPosition(x,y,z,new Vector3f());
        var dx=viewPosition(x+step,y,z,new Vector3f()).sub(origin).div(step);
        var dy=viewPosition(x,y+step,z,new Vector3f()).sub(origin).div(step);
        var dz=viewPosition(x,y,z+step,new Vector3f()).sub(origin).div(step);
        inverseWorldView.transformDirection(dx);
        inverseWorldView.transformDirection(dy);
        inverseWorldView.transformDirection(dz);
        var transform=new Matrix3f().setColumn(0,dx).setColumn(1,dy).setColumn(2,dz);
        if (!transform.isFinite() || Math.abs(transform.determinant())<1.0e-10f)
            throw new IllegalArgumentException("Degenerate projected hand Jacobian");
        return transform;
    }
}
