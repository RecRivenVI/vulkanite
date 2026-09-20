import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import me.cortex.vulkanite.compat.TerrainVertexCompatibility;
import static org.lwjgl.system.MemoryUtil.*;

class ValidateTerrainMaterials {
    public static void main(String[] args) {
        var format=VertexFormat.builder(0).addAttribute("a_Position",GpuFormat.RG32_UINT)
                .addAttribute("a_Color",GpuFormat.RGBA8_UNORM).addAttribute("a_TexCoord",GpuFormat.RG16_UINT)
                .addAttribute("a_LightAndData",GpuFormat.RGBA8_UINT).addAttribute("mc_Entity",GpuFormat.R32_UINT).build();
        var input=memCalloc(format.getVertexSize()*4);
        int[][] corners={{0,0,0},{1,0,0},{1,1,0},{0,1,0}};
        try {
            for(int material:new int[]{-1,0,100,101,110,120}) {
                for(int i=0;i<4;i++) {
                    int high=0,low=0,offset=i*format.getVertexSize();
                    for(int axis=0;axis<3;axis++) {
                        int q=(corners[i][axis]+8)*32768;
                        high|=(q>>>10)<<(axis*10); low|=(q&1023)<<(axis*10);
                    }
                    input.putInt(offset,high).putInt(offset+4,low).putInt(offset+8,0x407fa0c0);
                    input.putInt(offset+12,0x00010001).putInt(offset+16,0xf0f0);
                    input.putInt(offset+20,((material+1)<<1)|1);
                }
                var output=TerrainVertexCompatibility.convert(input,format);
                try {
                    for(int i=0;i<4;i++) {
                        int p=i*64;
                        if(output.getShort(p+32)!=(short)material || output.getShort(p+34)!=1 || output.getInt(p+8)!=0x407fa0c0)
                            throw new AssertionError("Lost material/fluid identity or separate-AO color: "+material);
                    }
                } finally {memFree(output);}
            }
            System.out.println("PASS terrain materials: unmapped, water, glass, lava, emitter IDs and separate AO survive ABI conversion");
        } finally {memFree(input);}
    }
}
