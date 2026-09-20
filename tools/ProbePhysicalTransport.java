import java.nio.file.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLError.*;

/** Executes the pack's actual GLSL material/atmosphere functions against analytic invariants. */
class ProbePhysicalTransport {
    static void near(float actual,double expected,double tolerance,String name) {
        if(!Float.isFinite(actual)||Math.abs(actual-expected)>tolerance) throw new AssertionError(name+": "+actual+" vs "+expected);
    }
    public static void main(String[] args) throws Exception {
        if(!SDL_Init(SDL_INIT_VIDEO)) throw new IllegalStateException(SDL_GetError());
        long window=0,context=0;
        try {
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,4); SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,5);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_CORE);
            window=SDL_CreateWindow("Foundation transport fixture",32,32,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN);
            if(window==0||(context=SDL_GL_CreateContext(window))==0||!SDL_GL_MakeCurrent(window,context)) throw new IllegalStateException(SDL_GetError());
            GL.createCapabilities();
            String source="#version 430\n"+Files.readString(Path.of("packs/Vulkanite-Foundation/shaders/lib/material.glsl"))
                +Files.readString(Path.of("packs/Vulkanite-Foundation/shaders/lib/atmosphere.glsl"))
                +Files.readString(Path.of("packs/Vulkanite-Foundation/shaders/lib/tonemap.glsl"))+"""
                layout(local_size_x=1) in;
                layout(std430,binding=0) buffer Result { vec4 values[]; };
                void main() {
                    values[0]=vec4(fresnelDielectric(1.0,1.0,1.333),fresnelDielectric(0.5,1.333,1.0),fresnelDielectric(0.0001,1.0,1.333),fresnelDielectric(1.0,1.333,1.0));
                    vec3 direction=vec3(sqrt(0.75),-0.5,0.0);
                    vec3 transmitted=refract(direction,vec3(0,1,0),1.0/1.333);
                    values[1]=vec4(transmitted,1.0);
                    values[2]=vec4(refract(transmitted,vec3(0,1,0),1.333),1.0);
                    values[3]=vec4(exp(-absorption(WATER)*10.0),neutralDisplay(vec3(0.01)).r);
                    values[4]=vec4(solarTransmission(planetPosition(65.0),vec3(0,1,0)),1.0);
                    values[5]=vec4(solarTransmission(planetPosition(65.0),vec3(0,-1,0)),1.0);
                    values[6]=vec4(skyRadiance(vec3(0,1,0),normalize(vec3(1,1,0)),65.0),1.0);
                    values[7]=vec4(srgbToLinear(vec3(0.04045,0.5,1.0)),neutralDisplay(vec3(0.0)).r);
                }
                """;
            int shader=glCreateShader(GL_COMPUTE_SHADER); glShaderSource(shader,source); glCompileShader(shader);
            if(glGetShaderi(shader,GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
            int program=glCreateProgram(); glAttachShader(program,shader); glLinkProgram(program); glDeleteShader(shader);
            if(glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
            int buffer=glCreateBuffers(); glNamedBufferData(buffer,128,GL_DYNAMIC_READ); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
            glUseProgram(program); glDispatchCompute(1,1,1); glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            float[] result=new float[32]; glGetNamedBufferSubData(buffer,0,result);
            double f0=Math.pow((1.333-1)/(1.333+1),2);
            near(result[0],f0,1e-5,"normal-incidence water Fresnel"); near(result[1],1,1e-6,"total internal reflection");
            near(result[2],1,0.002,"grazing reflection"); near(result[3],f0,1e-5,"reciprocity");
            near(result[4],Math.sqrt(.75)/1.333,1e-5,"Snell transmission");
            near(result[8],Math.sqrt(.75),1e-5,"slab exit direction X"); near(result[9],-.5,1e-5,"slab exit direction Y");
            for(int i=0;i<3;i++) near(result[12+i],Math.exp(-new double[]{.35,.065,.025}[i]*10),1e-5,"Beer attenuation");
            for(int i=0;i<3;i++) { if(result[16+i]<=0||result[16+i]>1) throw new AssertionError("solar transmission"); near(result[20+i],0,1e-6,"planet occludes night sun"); if(!Float.isFinite(result[24+i])||result[24+i]<=0) throw new AssertionError("day sky scattering"); }
            near(result[28],.04045/12.92,1e-6,"sRGB toe"); near(result[29],.21404114,1e-6,"sRGB midtone");
            near(result[31],0,0,"PBR Neutral preserves black"); near(result[15],.000625,1e-6,"PBR Neutral dark contrast");
            if(glGetError()!=GL_NO_ERROR) throw new AssertionError("GL error");
            glUseProgram(0); glDeleteBuffers(buffer); glDeleteProgram(program);
            exposure();
            System.out.println("PASS GPU physical transport: Fresnel, TIR, Snell, slab reciprocity, Beer attenuation, day/night atmosphere, sRGB");
        } finally {
            if(context!=0) SDL_GL_DestroyContext(context); if(window!=0) SDL_DestroyWindow(window); SDL_Quit();
        }
    }
    static void exposure() throws Exception {
        int shader=glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader,Files.readString(Path.of("packs/Vulkanite-Foundation/shaders/final.csh"))); glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
        int program=glCreateProgram(); glAttachShader(program,shader); glLinkProgram(program); glDeleteShader(shader);
        if(glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
        int scene=glCreateTextures(GL_TEXTURE_2D), meter=glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(scene,1,GL_RGBA32F,16,16); glTextureStorage2D(meter,1,GL_RGBA32F,1,1);
        glBindTextureUnit(0,scene); glBindImageTexture(1,meter,0,false,0,GL_READ_WRITE,GL_RGBA32F);
        glUseProgram(program); glUniform1i(glGetUniformLocation(program,"colortex0"),0); glUniform1i(glGetUniformLocation(program,"colorimg8"),1);
        glUniform1f(glGetUniformLocation(program,"frameTime"),1f/60); glUniform1i(glGetUniformLocation(program,"frameCounter"),0);
        for(float luminance:new float[]{.03f,1e-8f,0,10000}) {
            glClearTexImage(scene,0,GL_RGBA,GL_FLOAT,new float[]{luminance,luminance,luminance,1});
            glClearTexImage(meter,0,GL_RGBA,GL_FLOAT,new float[4]);
            glDispatchCompute(1,1,1); glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
            float[] result=new float[4]; glGetTextureImage(meter,0,GL_RGBA,GL_FLOAT,result);
            double target=Math.clamp(Math.log(.18/Math.max(luminance,1e-10))/Math.log(2),-8,22);
            near(result[0],target,1e-4,"HDR exposure "+luminance);
            if(luminance==0) near((float)(luminance*Math.pow(2,result[0])),0,0,"exposure cannot create light");
        }
        glClearTexImage(scene,0,GL_RGBA,GL_FLOAT,new float[]{.03f*512,.03f*512,.03f*512,1});
        glClearTexImage(meter,0,GL_RGBA,GL_FLOAT,new float[]{9,0,0,1});
        glDispatchCompute(1,1,1); glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        float[] invariant=new float[4]; glGetTextureImage(meter,0,GL_RGBA,GL_FLOAT,invariant);
        near(invariant[0],Math.log(6)/Math.log(2),1e-4,"meter invariant under pre-exposure");
        near(invariant[2],9,1e-6,"display removes actual input pre-exposure");
        // Sudden darkness must adapt gradually instead of changing exposure in one frame.
        glClearTexImage(scene,0,GL_RGBA,GL_FLOAT,new float[]{1e-8f,1e-8f,1e-8f,1});
        glClearTexImage(meter,0,GL_RGBA,GL_FLOAT,new float[]{0,0,0,1});
        glUniform1i(glGetUniformLocation(program,"frameCounter"),100);
        glDispatchCompute(1,1,1); glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        float[] adapted=new float[4]; glGetTextureImage(meter,0,GL_RGBA,GL_FLOAT,adapted);
        if(adapted[0]<=0||adapted[0]>=1) throw new AssertionError("Exposure did not adapt gradually");
        glUseProgram(0); glDeleteTextures(scene); glDeleteTextures(meter); glDeleteProgram(program);
        System.out.println("PASS GPU exposure: day, starlight, black, bright source and gradual dark adaptation");
    }
}
