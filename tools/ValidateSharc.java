import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import me.cortex.vulkanite.client.rendering.sharc.SharcRequest;
import me.cortex.vulkanite.lib.shader.ShaderCompiler;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;

class ValidateSharc {
    static final Path RUNTIME = Path.of("src/main/resources/assets/vulkanite/shaders/include/sharc");
    static final Path PACK = Path.of("packs/Vulkanite-Foundation/shaders");
    static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"\\s*$");

    static String expandRuntime(String name, Set<String> stack) throws Exception {
        if (!stack.add(name)) throw new AssertionError("recursive include " + name);
        String source = Files.readString(RUNTIME.resolve(name));
        Matcher matcher = INCLUDE.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(expandRuntime(matcher.group(1), stack)));
        matcher.appendTail(result);
        stack.remove(name);
        return result.toString();
    }

    static String expandPack(Path file) throws Exception {
        StringBuilder result = new StringBuilder();
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("#include \"/")) result.append(expandPack(PACK.resolve(line.substring(11, line.lastIndexOf('"')))));
            else result.append(line).append('\n');
        }
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        String compute = expandRuntime("sharc_resolve.comp", new HashSet<>());
        var spirv = ShaderCompiler.compileShader("sharc_resolve.comp", compute, VK_SHADER_STAGE_COMPUTE_BIT);
        var reflection = new ShaderReflection(spirv);
        if (reflection.getNSets() != 0) throw new AssertionError("SHaRC resolve unexpectedly requires descriptor sets");
        String raygen = ShaderCompiler.preprocessRaygen(expandPack(PACK.resolve("ray0.rgen")));
        SharcRequest request = SharcRequest.parse(raygen);
        if (request == null || !request.enabled() || request.capacityPower() != 20)
            throw new AssertionError("Foundation SHaRC request mismatch");
        for (String token : List.of("SharcUpdateHit", "SharcUpdateMiss", "SharcGetCachedRadiance", "vulkaniteSharcParameters"))
            if (!raygen.contains(token)) throw new AssertionError("missing SHaRC path: " + token);
        System.out.println("PASS SHaRC 1.8.3 compact cache request, Update/Query raygen and Resolve compute (" + spirv.remaining() + " bytes)");
    }
}
