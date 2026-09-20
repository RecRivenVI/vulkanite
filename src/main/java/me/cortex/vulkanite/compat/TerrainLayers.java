package me.cortex.vulkanite.compat;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups only exactly coincident, equally oriented producer quads, retaining every layer. */
public final class TerrainLayers {
    private static final int VERTEX = TerrainVertexCompatibility.STRIDE;
    private static final int QUAD = VERTEX * 4;
    private static final long POSITION_MASK = 0x0000ffffffffffffL;

    public record LayeredMesh(ByteBuffer bytes, int canonicalQuadCount, boolean requiresAnyHit) {}
    private record Key(long a,long b,long c,long d,int normal,int twoSided,int unique) {}
    private record Face(int bufferIndex,int byteOffset,int passIndex,int quadIndex,Key key) {}
    private static final class Group {
        final List<Face> layers = new ArrayList<>();
        Face canonical() { return layers.getFirst(); }
    }

    private TerrainLayers() {}

    private static int passIndex(TerrainRenderPass pass) {
        for (var known : DefaultTerrainRenderPasses.ALL) if (pass == known)
            return DefaultTerrainRenderPasses.getPassIndex(pass);
        return -1;
    }

    private static Key key(ByteBuffer data,int start,int unique) {
        int normal=data.getInt(start+28)&0xffffff;
        return new Key(data.getLong(start)&POSITION_MASK,
                data.getLong(start+VERTEX)&POSITION_MASK,
                data.getLong(start+VERTEX*2)&POSITION_MASK,
                data.getLong(start+VERTEX*3)&POSITION_MASK,normal,
                data.getInt(start+48)&TerrainVertexCompatibility.TWO_SIDED,unique);
    }

    /** Output buffers are newly owned; source buffers remain caller-owned even on failure. */
    public static List<LayeredMesh> compose(List<ByteBuffer> sources,List<TerrainRenderPass> passes) {
        if(sources.size()!=passes.size()) throw new IllegalArgumentException("Terrain source/pass count differs");
        var faces=new ArrayList<Face>();
        int unique=1;
        for(int buffer=0;buffer<sources.size();buffer++) {
            var data=sources.get(buffer).duplicate().order(ByteOrder.nativeOrder());
            if(data.remaining()%QUAD!=0) throw new IllegalArgumentException("Incomplete terrain producer quad");
            int rank=passIndex(passes.get(buffer));
            for(int quad=0;quad<data.remaining()/QUAD;quad++) {
                int start=data.position()+quad*QUAD;
                // Unknown pass order cannot establish a cross-draw layer; keep it independent.
                faces.add(new Face(buffer,start,rank,quad,key(data,start,rank<0?unique++:0)));
            }
        }
        faces.sort(Comparator.comparingInt((Face face)->face.passIndex()<0?Integer.MAX_VALUE:face.passIndex())
                .thenComparingInt(Face::quadIndex).thenComparingInt(Face::bufferIndex));
        Map<Key,Group> groups=new LinkedHashMap<>();
        for(var face:faces) groups.computeIfAbsent(face.key(),ignored->new Group()).layers.add(face);
        List<List<Group>> byBuffer=new ArrayList<>(sources.size());
        for(int i=0;i<sources.size();i++) byBuffer.add(new ArrayList<>());
        for(var group:groups.values()) byBuffer.get(group.canonical().bufferIndex()).add(group);
        var result=new ArrayList<LayeredMesh>(sources.size());
        try {
            for(int buffer=0;buffer<sources.size();buffer++) {
                var canonical=byBuffer.get(buffer);
                int baseBytes=Math.multiplyExact(canonical.size(),QUAD);
                int layerQuads=0;
                for(var group:canonical) if(group.layers.size()>1)
                    layerQuads=Math.addExact(layerQuads,group.layers.size());
                int totalBytes=Math.addExact(baseBytes,Math.multiplyExact(layerQuads,QUAD));
                var output=MemoryUtil.memAlloc(Math.max(totalBytes,1)).order(ByteOrder.nativeOrder());
                output.limit(totalBytes);
                result.add(new LayeredMesh(output,canonical.size(),false));
                boolean requiresAnyHit=false;
                int nextLayerByte=baseBytes;
                for(int quad=0;quad<canonical.size();quad++) {
                    var group=canonical.get(quad);
                    int canonicalByte=quad*QUAD;
                    copyQuad(sources.get(group.canonical().bufferIndex()),group.canonical().byteOffset(),
                            output,canonicalByte);
                    if(group.layers.size()>1) {
                        int startWords=Math.floorDiv(nextLayerByte,Integer.BYTES);
                        for(int vertex=0;vertex<4;vertex++) {
                            int at=canonicalByte+vertex*VERTEX;
                            output.putInt(at+40,startWords);
                            output.putInt(at+44,group.layers.size());
                        }
                        for(var layer:group.layers) {
                            copyQuad(sources.get(layer.bufferIndex()),layer.byteOffset(),output,nextLayerByte);
                            nextLayerByte+=QUAD;
                            var pass=passes.get(layer.bufferIndex());
                            if(pass.supportsFragmentDiscard()||pass.isTranslucent()) requiresAnyHit=true;
                        }
                        int combinedFlags=0;
                        for(var layer:group.layers) combinedFlags|=sources.get(layer.bufferIndex())
                                .getInt(layer.byteOffset()+48);
                        for(int vertex=0;vertex<4;vertex++) {
                            int at=canonicalByte+vertex*VERTEX;
                            output.putInt(at+48,output.getInt(at+48)
                                    |(combinedFlags&(TerrainVertexCompatibility.TWO_SIDED
                                    |TerrainVertexCompatibility.ALPHA_DISCARD
                                    |TerrainVertexCompatibility.ALPHA_BLEND)));
                        }
                    } else if ((output.getInt(canonicalByte+48)&(TerrainVertexCompatibility.ALPHA_DISCARD
                            |TerrainVertexCompatibility.ALPHA_BLEND))!=0)
                        requiresAnyHit=true;
                }
                if(nextLayerByte!=totalBytes) throw new IllegalStateException("Terrain layer directory exceeds buffer");
                result.set(buffer,new LayeredMesh(output,canonical.size(),requiresAnyHit));
            }
            return List.copyOf(result);
        } catch(Throwable failure) {
            for(var mesh:result) MemoryUtil.memFree(mesh.bytes());
            throw failure;
        }
    }

    private static void copyQuad(ByteBuffer source,int sourceOffset,ByteBuffer target,int targetOffset) {
        if(sourceOffset<0 || sourceOffset>source.limit()-QUAD
                || targetOffset<0 || targetOffset>target.limit()-QUAD)
            throw new IllegalArgumentException("Terrain layer copy exceeds a source or destination buffer");
        MemoryUtil.memCopy(MemoryUtil.memAddress(source)+sourceOffset,
                MemoryUtil.memAddress(target)+targetOffset,QUAD);
    }
}
