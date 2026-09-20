import java.nio.file.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLError.*;
class ProbeVisibilityRaster {
 static Path root=Path.of("packs/Vulkanite-Foundation/shaders");
 static String expand(Path p) throws Exception {
  StringBuilder s=new StringBuilder();
  for(String line:Files.readAllLines(p)) {
   if(line.startsWith("#include \"/")) s.append(expand(root.resolve(line.substring(11,line.lastIndexOf('"')))));
   else s.append(line).append('\n');
  }
  return s.toString();
 }
 static int compile(int type,String source) {
  int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);
  if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new AssertionError(glGetShaderInfoLog(s));
  return s;
 }
 static void near(float value,double expected,String name) {
  if(Math.abs(value-expected)>0.002)throw new AssertionError(name+": "+value+" != "+expected);
 }
 static void probability(int count,double expected,String name) {
  double observed=count/65536.0;
  double tolerance=4*Math.sqrt(expected*(1-expected)/65536.0);
  if(Math.abs(observed-expected)>tolerance)throw new AssertionError(name+": "+observed);
 }
 static void drawQuad() {
  org.lwjgl.opengl.GL11.glColor4f(1,1,1,1);
  org.lwjgl.opengl.GL13.glMultiTexCoord2f(GL_TEXTURE0,0.5f,0.5f);
  org.lwjgl.opengl.GL13.glMultiTexCoord2f(GL_TEXTURE1,0.5f,0.5f);
  org.lwjgl.opengl.GL11.glBegin(GL_QUADS);
  org.lwjgl.opengl.GL11.glVertex3f(-1,-1,0);org.lwjgl.opengl.GL11.glVertex3f(1,-1,0);
  org.lwjgl.opengl.GL11.glVertex3f(1,1,0);org.lwjgl.opengl.GL11.glVertex3f(-1,1,0);
  org.lwjgl.opengl.GL11.glEnd();
 }
 static void testLayer(int program) {
  int color=glCreateTextures(GL_TEXTURE_2D),depth=glCreateTextures(GL_TEXTURE_2D);
  int surface=glCreateTextures(GL_TEXTURE_2D),light=glCreateTextures(GL_TEXTURE_2D),fbo=glCreateFramebuffers();
  try {
   glTextureStorage2D(color,1,GL_RGBA16F,16,16);glTextureStorage2D(depth,1,GL_DEPTH_COMPONENT32F,16,16);
   glTextureStorage2D(surface,1,GL_RGBA32F,1,1);glTextureStorage2D(light,1,GL_RGBA32F,1,1);
   glTextureSubImage2D(surface,0,0,0,1,1,GL_RGBA,GL_FLOAT,new float[]{0.8f,0.4f,0.2f,0.5f});
   glTextureSubImage2D(light,0,0,0,1,1,GL_RGBA,GL_FLOAT,new float[]{1,1,1,1});
   glNamedFramebufferTexture(fbo,GL_COLOR_ATTACHMENT0,color,0);glNamedFramebufferTexture(fbo,GL_DEPTH_ATTACHMENT,depth,0);
   if(glCheckNamedFramebufferStatus(fbo,GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO");
   glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,16,16);glUseProgram(program);
   glUniform1i(glGetUniformLocation(program,"gtexture"),0);glUniform1i(glGetUniformLocation(program,"lightmap"),1);
   glBindTextureUnit(0,surface);glBindTextureUnit(1,light);
   glClearColor(0,0,0,0);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
   glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
   glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LESS);glDepthMask(false);
   drawQuad();drawQuad();
   float[] pixel=new float[4];glReadPixels(8,8,1,1,GL_RGBA,GL_FLOAT,pixel);
   near(pixel[3],0,"PT geometry must not also write raster coverage");
   near(pixel[0],0,"PT geometry must not also write raster color");
   glDepthMask(true);glClearDepth(0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
   drawQuad();glReadPixels(8,8,1,1,GL_RGBA,GL_FLOAT,pixel);near(pixel[3],0,"world depth occlusion");
   glClearDepth(1);glClear(GL_DEPTH_BUFFER_BIT);
   glTextureSubImage2D(surface,0,0,0,1,1,GL_RGBA,GL_FLOAT,new float[]{1,1,1,0});
   drawQuad();glReadPixels(8,8,1,1,GL_RGBA,GL_FLOAT,pixel);near(pixel[3],0,"transparent texel");
   if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error");
   System.out.println("PASS GPU: PT-only hands/particles leave raster color and coverage empty");
  } finally {
   glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glUseProgram(0);glBindFramebuffer(GL_FRAMEBUFFER,0);
   glDeleteFramebuffers(fbo);glDeleteTextures(color);glDeleteTextures(depth);glDeleteTextures(surface);glDeleteTextures(light);
  }
 }
 static void testCoverage() throws Exception {
  String source="#version 430\n"+expand(root.resolve("lib/coverage.glsl"))+expand(root.resolve("lib/visibility.glsl"))+"""
   layout(local_size_x=1) in;
   layout(std430,binding=0) buffer Results { uint counts[6]; };
   void main() {
    for(uint i=0u;i<5u;i++) counts[i]=0u;
    counts[5]=uint(visibleToRay(4u,1u) && !visibleToRay(4u,0u) && visibleToRay(4u,2u)
                  && !visibleToRay(2u,1u) && visibleToRay(2u,0u) && visibleToRay(16u,0u) && visibleToRay(16u,1u));
    for(uint seed=0u;seed<65536u;seed++) {
     if(covered(0.0,seed,0u,0u)) counts[0]++;
     if(covered(0.2,seed,0u,0u)) counts[1]++;
     if(covered(0.8,seed,0u,0u)) counts[2]++;
     if(covered(1.0,seed,0u,0u)) counts[3]++;
     if(!covered(0.2,seed,0u,0u)&&!covered(0.8,seed,1u,0u)) counts[4]++;
    }
   }
   """;
  int shader=compile(GL_COMPUTE_SHADER,source),program=glCreateProgram(),buffer=glCreateBuffers();
  try {
   glAttachShader(program,shader);glLinkProgram(program);
   if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new AssertionError(glGetProgramInfoLog(program));
   glNamedBufferData(buffer,24,GL_DYNAMIC_READ);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
   glUseProgram(program);glDispatchCompute(1,1,1);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
   int[] counts=new int[6];glGetNamedBufferSubData(buffer,0,counts);
   if(counts[5]!=1)throw new AssertionError("camera-only hands / world body / full-scene particles visibility");
   if(counts[0]!=0||counts[3]!=65536)throw new AssertionError("coverage endpoints");
   probability(counts[1],0.2,"20 percent opacity");probability(counts[2],0.8,"80 percent opacity");
   probability(counts[4],0.16,"two-layer ray transmittance");
   System.out.println("PASS GPU stochastic coverage: endpoints, 20/80 percent alpha and layered ray transmittance");
   System.out.println("PASS GPU ray visibility: camera-only hands, body shadows, full-scene particles, glass exit");
  } finally {glUseProgram(0);glDeleteBuffers(buffer);glDeleteProgram(program);glDeleteShader(shader);}
 }
 public static void main(String[] args) throws Exception {
  if(!SDL_Init(SDL_INIT_VIDEO))throw new IllegalStateException(SDL_GetError());
  long w=0,c=0;
  try {
   SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,4);SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,5);
   SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_COMPATIBILITY);
   w=SDL_CreateWindow("Visibility shader fixture",16,16,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN);
   c=SDL_GL_CreateContext(w);if(c==0||!SDL_GL_MakeCurrent(w,c))throw new IllegalStateException(SDL_GetError());
   GL.createCapabilities();
   for(String name:new String[]{"gbuffers_hand","gbuffers_particles","final"}) {
    int v=compile(GL_VERTEX_SHADER,expand(root.resolve(name+".vsh")));
    int f=compile(GL_FRAGMENT_SHADER,expand(root.resolve(name+".fsh")));
    int p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);
    if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new AssertionError(glGetProgramInfoLog(p));
    if(!name.equals("final"))testLayer(p);
    glDeleteProgram(p);glDeleteShader(v);glDeleteShader(f);
    System.out.println("PASS OpenGL compile/link "+name);
   }
   testCoverage();
  } finally {if(c!=0)SDL_GL_DestroyContext(c);if(w!=0)SDL_DestroyWindow(w);SDL_Quit();}
 }
}

