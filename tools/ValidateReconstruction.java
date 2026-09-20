import java.nio.*;
import me.cortex.vulkanite.compat.EntityMotionHistory;
import me.cortex.vulkanite.client.rendering.reconstruction.*;
import me.cortex.vulkanite.lib.shader.ShaderCompiler;
import org.joml.*;

class ValidateReconstruction {
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static ByteBuffer vertex(float x, float u) {
        var b=ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        b.putFloat(0,x).putFloat(16,u); return b;
    }
    static String request="const int vulkaniteReconstructionVersion=1; const int vulkaniteReconstructionMode=2; const int vulkaniteReconstructionQuality=2; const int vulkaniteReconstructionOutput=0;";
    public static void main(String[] args) {
        check(ReconstructionRequest.parse("// "+request)==null,"comment opt-out");
        check(ReconstructionRequest.parse(request).mode()==2,"RR request");
        check(ReconstructionRequest.parse(request.replace("Version=1","Version=2")).version()==2,"HDR32 request");
        check(ReconstructionRequest.parse(request.replace("Version=1","Version=2")+"const int vulkaniteReconstructionExposure=8;").exposureTarget()==8,"exposure target");
        for(String bad:new String[]{request.replace("Version=1","Version=3"),request.replace("Mode=2","Mode=3"),request.replace("Output=0","Output=16"),request+"const int vulkaniteReconstructionMode=1;"}) {
            try { ReconstructionRequest.parse(bad); throw new AssertionError("invalid request accepted"); }
            catch(IllegalArgumentException expected) {}
        }
        String source="#version 460\n#define MODE 1\n#if MODE == 1\n"+request.replace("Mode=2","Mode=MODE")+"\n#else\n"+request+"\n#endif\nvoid main() {}\n";
        check(ReconstructionRequest.parse(ShaderCompiler.preprocessRaygen(source)).mode()==1,"active macro branch");
        var p=new Matrix4f().perspective((float)java.lang.Math.toRadians(70),16f/9f,0.05f,1024f);
        var reverse=ReconstructionMath.reverseZeroToOne(p);
        check(!ReconstructionMath.projectionCut(new Matrix4f(reverse).rotateZ(0.01f).translate(0.01f,0.02f,0),reverse),"view bob retains history");
        check(!ReconstructionMath.projectionCut(new Matrix4f(reverse).scale(1.01f,1.01f,1),reverse),"smooth FOV retains history");
        check(ReconstructionMath.projectionCut(new Matrix4f(reverse).scale(2,2,1),reverse),"large FOV cut resets");
        var point=new Vector3f();
        check(java.lang.Math.abs(reverse.transformProject(0,0,-0.05f,point).z-1)<0.00001f,"near reverse depth");
        check(java.lang.Math.abs(reverse.transformProject(0,0,-1024f,point).z)<0.00001f,"far reverse depth");
        var standard=p.transformProject(1,2,-10,new Vector3f());
        reverse.transformProject(1,2,-10,point);
        check(java.lang.Math.abs(point.x-standard.x)<0.00001f&&java.lang.Math.abs(point.y-standard.y)<0.00001f,"XY preserved");
        var history=new EntityMotionHistory(); var world=new Object();
        history.begin(world); var a=vertex(1,0); var b=vertex(10,0);
        history.apply("a",a); history.apply("b",b); history.end();
        check(a.getInt(44)==0,"new entity invalid history");
        history.begin(world); b=vertex(11,0); a=vertex(2,0);
        history.apply("b",b); history.apply("a",a); history.end();
        check(a.getFloat(32)==1&&b.getFloat(32)==10&&a.getInt(44)==1,"entity reordering and motion");
        history.begin(world); a=vertex(3,1); history.apply("a",a); history.end();
        check(a.getInt(44)==0&&a.getFloat(32)==3,"topology change rejection");
        history.begin(world); b=vertex(12,0); history.apply("b",b); history.end();
        check(b.getInt(44)==0,"disappearance invalidates history");
        history.begin(new Object()); b=vertex(13,0); history.apply("b",b); history.end();
        check(b.getInt(44)==0,"world change invalidates history");
        System.out.println("PASS reconstruction requests, macro branches, reverse depth, entity motion identity/topology/world changes");
    }
}
