import java.nio.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import me.cortex.vulkanite.compat.*;
import static org.lwjgl.system.MemoryUtil.*;
class ValidateDynamicGeometry {
 static void near(float a,float b,String label){if(!Float.isFinite(a)||Math.abs(a-b)>0.0003)throw new AssertionError(label+": "+a+" vs "+b);}
 public static void main(String[] args) {
  // Camera motion, world FOV changes and bobbing must not change the intended HUD angular projection.
  for(float fov:new float[]{50,70,110})for(float aspect:new float[]{1,16f/9,2.4f}) {
   var view=new Matrix4f().rotateY(0.6f).rotateX(-0.2f).translate(-120,-65,80);
   var projection=new Matrix4f().perspective((float)Math.toRadians(fov),aspect,0.05f,1024);
   var bob=new Matrix4f().translate(0.01f,-0.02f,0).rotateZ(0.04f);
   var point=new Vector3f(0.3f,-0.2f,-0.7f);
   var expected=new Matrix4f().perspective((float)Math.toRadians(70),aspect,0.05f,1024).mul(bob).transformProject(new Vector3f(point));
   var transform=ViewModelTransform.create(view,projection,70,aspect,bob);
   var actual=new Matrix4f(projection).mul(view).mul(transform).transformProject(new Vector3f(point));
   near(actual.x,expected.x,"hand projection x");near(actual.y,expected.y,"hand projection y");
   near(actual.z,expected.z,"uncompressed hand depth");
  }
  var vertices=memCalloc(EntityFrame.STRIDE*4);
  try {
   var history=new EntityMotionHistory();Object world=new Object();Object particle=new Object();
   for(int i=0;i<4;i++){int b=i*EntityFrame.STRIDE;vertices.putFloat(b,i).putFloat(b+4,2).putFloat(b+8,3);vertices.putFloat(b+16,i/4f);}
   EntityVertexCompatibility.transform(vertices,new Matrix4f().translation(10,20,30));
   history.begin(world);history.apply(particle,vertices);history.end();
   EntityVertexCompatibility.transform(vertices,new Matrix4f().translation(1,-2,3));
   history.begin(world);history.apply(particle,vertices);history.end();
   for(int i=0;i<4;i++) {
    int b=i*EntityFrame.STRIDE;near(vertices.getFloat(b),11+i,"current world x");near(vertices.getFloat(b+32),10+i,"previous world x");
    near(vertices.getFloat(b+36),22,"previous world y");
    if(vertices.getInt(b+44)!=EntityFrame.HISTORY_VALID)throw new AssertionError("history lost");
    int flags=vertices.getInt(b+44)|EntityFrame.PARTICLE|EntityFrame.ALPHA_COVERAGE|(121<<16);
    if((flags&1)!=1 || flags>>>16!=121)throw new AssertionError("flags/material overlap");
   }
   history.begin(new Object());history.apply(particle,vertices);history.end();
   if(vertices.getInt(44)!=0)throw new AssertionError("cross-world history reused");
   history.clear();
  } finally {memFree(vertices);}
  System.out.println("PASS dynamic geometry: hand FOV/bob projection, world-space temporal positions, flags and world reset");
 }
}

