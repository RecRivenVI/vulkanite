import java.nio.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import me.cortex.vulkanite.compat.EntityVertexCompatibility;
import static org.lwjgl.system.MemoryUtil.*;
class ValidateEntityVertices {
 public static void main(String[] args) {
  var format=VertexFormat.builder(0).addAttribute("UV0",GpuFormat.RG32_FLOAT).addAttribute("Color",GpuFormat.RGBA8_UNORM).addAttribute("Position",GpuFormat.RGB32_FLOAT).addAttribute("Normal",GpuFormat.RGBA8_SNORM).build();
  var input=memAlloc(16+format.getVertexSize()*4); var output=memAlloc(48*4);
  try {
   input.position(16);
   for(int i=0;i<4;i++) input.putFloat(i/4f).putFloat(1f).putInt(0xFF804020).putFloat(100+i).putFloat(65).putFloat(-200).putInt(0x007F0000);
   input.flip().position(16);
   EntityVertexCompatibility.append(input,format,13,output);output.flip();
   for(int i=0;i<4;i++) { int b=i*48;if(output.getFloat(b)!=100+i||output.getFloat(b+8)!=-200||output.getInt(b+12)!=0xFF804020||output.getFloat(b+16)!=i/4f||output.getInt(b+24)!=0x007F0000||output.getInt(b+28)!=13||output.getFloat(b+32)!=100+i||output.getInt(b+44)!=0)throw new AssertionError("ABI mismatch at vertex "+i); }
   input.limit(input.limit()-1);
   try {EntityVertexCompatibility.append(input,format,0,output);throw new AssertionError("Accepted truncated quad");}catch(IllegalArgumentException expected){}
   System.out.println("PASS entity ABI: reordered attributes, nonzero input offset, world position, RGBA, UV, normal, texture id, malformed quad rejection");
  } finally {memFree(input);memFree(output);}
 }
}
