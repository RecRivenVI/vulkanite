import java.nio.file.*;
import java.util.*;
import me.cortex.vulkanite.lib.shader.ShaderCompiler;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
class ValidateFoundation {
 static Path root=Path.of("packs/Vulkanite-Foundation/shaders");
 static String expand(Path file) throws Exception {
   StringBuilder result=new StringBuilder();
   for(String line:Files.readAllLines(file)) {
     if(line.startsWith("#include \"/")) result.append(expand(root.resolve(line.substring(11,line.lastIndexOf('"')))));
     else result.append(line).append('\n');
   }
   return result.toString();
 }
 public static void main(String[] args) throws Exception {
   var stages=new ArrayList<ShaderReflection>();
   try(var files=Files.list(root)) {
     for(var f:files.filter(p->p.toString().matches(".*\\.(rgen|rchit|rahit|rmiss)$")).sorted().toList()) {
       String n=f.toString(); int stage=n.endsWith("rgen")?VK_SHADER_STAGE_RAYGEN_BIT_KHR:n.endsWith("rchit")?VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR:n.endsWith("rahit")?VK_SHADER_STAGE_ANY_HIT_BIT_KHR:VK_SHADER_STAGE_MISS_BIT_KHR;
       var code=ShaderCompiler.compileShader(n,expand(f),stage);
       if(n.endsWith("rgen")) {
         var request=me.cortex.vulkanite.client.rendering.reconstruction.ReconstructionRequest.parse(ShaderCompiler.preprocessRaygen(expand(f)));
         if(request.mode()!=2) throw new AssertionError("Default RR request lost");
       }
       stages.add(new ShaderReflection(code)); System.out.println("PASS "+f.getFileName()+" "+code.remaining()+" bytes");
       if(n.endsWith("rgen")) for(int mode=1;mode<=4;mode++) {
         ShaderCompiler.compileShader(n,expand(f).replace("#define VIEW_MODE 0", "#define VIEW_MODE "+mode),stage);
         var request=me.cortex.vulkanite.client.rendering.reconstruction.ReconstructionRequest.parse(ShaderCompiler.preprocessRaygen(expand(f).replace("#define VIEW_MODE 0", "#define VIEW_MODE "+mode)));
         if(request.mode()!=0) throw new AssertionError("Diagnostics must disable reconstruction");
         System.out.println("PASS VIEW_MODE="+mode);
       }
     }
   }
   var merged=ShaderReflection.mergeStages(stages.toArray(ShaderReflection[]::new));
   if(merged.getSet(0).getBindingAt(7).arraySize()!=256) throw new AssertionError("Entity ABI declaration lost");
   System.out.println(merged);
 }
}
