import me.cortex.vulkanite.client.rendering.UiCompositor;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLError.*;

/** GPU reference-blending comparison, independent of Minecraft UI interaction. */
public final class ProbeUiComposition {
    public static void main(String[] args) throws Exception {
        if (!SDL_Init(SDL_INIT_VIDEO)) throw new IllegalStateException(SDL_GetError());
        long window = 0, context = 0;
        try {
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 4);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 5);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
            window = SDL_CreateWindow("Vulkanite UI GPU fixture", 64, 48, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
            if (window == 0 || (context = SDL_GL_CreateContext(window)) == 0) throw new IllegalStateException(SDL_GetError());
            if (!SDL_GL_MakeCurrent(window, context)) throw new IllegalStateException(SDL_GetError());
            GL.createCapabilities();
            try (var compositor = new UiCompositor()) {
                run(compositor, 64, 48);
                run(compositor, 137, 79);
            }
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL error");
            System.out.println("PASS GPU UI: overlapping translucent layers, transparent background, resize, compute state restoration");
        } finally {
            if (context != 0) SDL_GL_DestroyContext(context);
            if (window != 0) SDL_DestroyWindow(window);
            SDL_Quit();
        }
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }

    private static void run(UiCompositor compositor, int width, int height) throws Exception {
        int[] textures = new int[3];
        int fbo = glCreateFramebuffers(), vao = glCreateVertexArrays(), program = glCreateProgram();
        int vs = shader(GL_VERTEX_SHADER, "#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0,1);}");
        int fs = shader(GL_FRAGMENT_SHADER, "#version 430\nuniform vec4 color; layout(location=0) out vec4 result; void main(){result=color;}");
        glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
        glDeleteShader(vs); glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
        ByteBuffer actual = MemoryUtil.memAlloc(width * height * 4), expected = MemoryUtil.memAlloc(width * height * 4);
        try {
            for (int i = 0; i < 3; i++) {
                textures[i] = glCreateTextures(GL_TEXTURE_2D);
                glTextureStorage2D(textures[i], 1, GL_RGBA8, width, height);
                glClearTexImage(textures[i], 0, GL_RGBA, GL_FLOAT, i == 1 ? new float[]{0,0,0,0} : new float[]{0.23f,0.41f,0.67f,1});
            }
            glUseProgram(program); glBindVertexArray(vao); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, width, height); glDisable(GL_DITHER); glEnable(GL_SCISSOR_TEST);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            for (int texture : new int[]{textures[0], textures[1]}) {
                glNamedFramebufferTexture(fbo, GL_COLOR_ATTACHMENT0, texture, 0);
                if (glCheckNamedFramebufferStatus(fbo, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("FBO");
                glScissor(3, 4, width / 2, height / 2);
                glUniform4f(glGetUniformLocation(program, "color"), 0.8f, 0.2f, 0.1f, 0.3f); glDrawArrays(GL_TRIANGLES, 0, 3);
                glScissor(width / 3, height / 3, width / 2, height / 2);
                glUniform4f(glGetUniformLocation(program, "color"), 0.1f, 0.9f, 0.3f, 0.65f); glDrawArrays(GL_TRIANGLES, 0, 3);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindImageTexture(0, textures[0], 0, false, 0, GL_READ_ONLY, GL_RGBA8);
            glBindImageTexture(1, textures[1], 0, false, 0, GL_READ_WRITE, GL_RGBA8);
            var compose = UiCompositor.class.getDeclaredMethod("composite", int.class, int.class, int.class, int.class);
            compose.setAccessible(true);
            compose.invoke(compositor, textures[1], textures[2], width, height);
            if (glGetInteger(GL_CURRENT_PROGRAM) != program || glGetIntegeri(GL_IMAGE_BINDING_NAME, 0) != textures[0]
                    || glGetIntegeri(GL_IMAGE_BINDING_NAME, 1) != textures[1]
                    || glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 1) != GL_READ_WRITE) throw new AssertionError("Compute leaked GL state");
            glGetTextureImage(textures[0], 0, GL_RGBA, GL_UNSIGNED_BYTE, expected);
            glGetTextureImage(textures[2], 0, GL_RGBA, GL_UNSIGNED_BYTE, actual);
            int max = 0;
            for (int i = 0; i < actual.capacity(); i++) max = Math.max(max, Math.abs(Byte.toUnsignedInt(actual.get(i)) - Byte.toUnsignedInt(expected.get(i))));
            if (max > 2) throw new AssertionError("Recomposition mismatch: " + max);
            glGetTextureImage(textures[1], 0, GL_RGBA, GL_UNSIGNED_BYTE, actual);
            if (actual.get(3) != 0) throw new AssertionError("Untouched UI alpha must be zero");
            System.out.println("GPU fixture " + width + "x" + height + " maxRgba8=" + max);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glUseProgram(0); glBindVertexArray(0);
            glBindImageTexture(0, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
            glBindImageTexture(1, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
            glDeleteTextures(textures); glDeleteFramebuffers(fbo); glDeleteVertexArrays(vao); glDeleteProgram(program);
            MemoryUtil.memFree(actual); MemoryUtil.memFree(expected);
        }
    }
}
