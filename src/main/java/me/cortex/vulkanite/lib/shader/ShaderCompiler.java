package me.cortex.vulkanite.lib.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

public class ShaderCompiler {
    public static String preprocessRaygen(String source) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("Failed to create shader preprocessor");
        long result = 0;
        ByteBuffer nativeSource = null;
        try (var stack = stackPush()) {
            // Avoid the CharSequence overload: it copies the entire source onto MemoryStack.
            nativeSource = memUTF8(source, false);
            result = shaderc_compile_into_preprocessed_text(compiler, nativeSource, shaderc_raygen_shader,
                    stack.UTF8("raygen"), stack.UTF8("main"), 0);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalArgumentException("Raygen preprocessing failed: " + (result == 0 ? "no result" : shaderc_result_get_error_message(result)));
            return org.lwjgl.system.MemoryUtil.memUTF8(shaderc_result_get_bytes(result));
        } finally {
            memFree(nativeSource);
            if (result != 0) shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    private static int vulkanStageToShadercKind(int stage) {
        switch (stage) {
            case VK_SHADER_STAGE_VERTEX_BIT:
                return shaderc_vertex_shader;
            case VK_SHADER_STAGE_FRAGMENT_BIT:
                return shaderc_fragment_shader;
            case VK_SHADER_STAGE_RAYGEN_BIT_KHR:
                return shaderc_raygen_shader;
            case VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR:
                return shaderc_closesthit_shader;
            case VK_SHADER_STAGE_MISS_BIT_KHR:
                return shaderc_miss_shader;
            case VK_SHADER_STAGE_ANY_HIT_BIT_KHR:
                return shaderc_anyhit_shader;
            case VK_SHADER_STAGE_INTERSECTION_BIT_KHR:
                return shaderc_intersection_shader;
            case VK_SHADER_STAGE_COMPUTE_BIT:
                return shaderc_compute_shader;
            default:
                throw new IllegalArgumentException("Stage: " + stage);
        }
    }

    public static ByteBuffer compileShader(String filename, String source, int vulkanStage) {
        // Include native compiler identity, source (with all includes/options expanded),
        // debug filename and every code-generation setting. Keep reflection names intact.
        String key = cacheKey(filename, source, vulkanStage);
        byte[] cached = key == null ? null : CompilationCache.read("spirv-v1", key, 16 * 1024 * 1024);
        if (cached != null && validSpirv(cached)) return direct(cached);
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new RuntimeException("Failed to create shader compiler");
        }
        long options = shaderc_compile_options_initialize();
        long result = 0;
        ByteBuffer nativeSource = null;
        try (var stack = stackPush()) {
            if (options == 0) throw new IllegalStateException("Failed to create shader compiler options");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_4);
            // SPIR-V reflection names are part of the scene/frame ABI checks.
            // With shaderc optimization enabled, omitting this drops all binding names.
            shaderc_compile_options_set_generate_debug_info(options);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

            nativeSource = memUTF8(source, false);
            result = shaderc_compile_into_spv(compiler, nativeSource, vulkanStageToShadercKind(vulkanStage),
                    stack.UTF8(filename), stack.UTF8("main"), options);

            if (result == 0) {
                throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
            }

            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V:\n " + shaderc_result_get_error_message(result));
            }
            ByteBuffer code = shaderc_result_get_bytes(result);
            byte[] bytes = new byte[code.remaining()]; code.get(bytes);
            if (key != null) CompilationCache.write("spirv-v1", key, bytes);
            return direct(bytes);
        } finally {
            memFree(nativeSource);
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    public static String cacheKey(String filename, String source, int stage) {
        if (CompilerIdentity.HASH == null) return null;
        return CompilationCache.key("shaderc-v1/vulkan1.2/spirv1.4/performance/debug/main",
                CompilerIdentity.HASH, Integer.toString(stage), filename, source);
    }

    private static ByteBuffer direct(byte[] bytes) {
        return ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder()).put(bytes).flip();
    }

    private static boolean validSpirv(byte[] bytes) {
        if (bytes.length < 20 || (bytes.length & 3) != 0) return false;
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt(0) != 0x07230203 || data.getInt(4) != 0x00010400 || data.getInt(16) != 0) return false;
        for (int offset = 20; offset < bytes.length;) {
            int words = data.getInt(offset) >>> 16;
            if (words == 0 || words * 4 > bytes.length - offset) return false;
            offset += words * 4;
        }
        return true;
    }

    private static final class CompilerIdentity {
        static final String HASH = identify();
        private static String identify() {
            try { return CompilationCache.hash(Files.readAllBytes(Path.of(org.lwjgl.util.shaderc.Shaderc.getLibrary().getPath()))); }
            catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("Vulkanite/Compilation").warn(
                        "Cannot identify native shaderc; persistent SPIR-V cache disabled: {}", error.toString());
                return null;
            }
        }
    }
}
