package me.cortex.vulkanite.compat;

import java.nio.ByteBuffer;
import java.util.*;

/** Collapses a base and a tinted, coplanar overlay into one ray-traceable surface. */
public final class TerrainLayers {
    private static final int VERTEX = TerrainVertexCompatibility.STRIDE;
    private static final int QUAD = VERTEX * 4;
    private record Face(ByteBuffer data, int offset) {}
    private record Key(List<Long> corners, int normal) {}

    private static long corner(ByteBuffer data, int offset) {
        return (data.getLong(offset) & 0x0000ffffffffffffL);
    }
    private static Key key(Face face) {
        var corners = new ArrayList<Long>(4);
        for (int i = 0; i < 4; i++) corners.add(corner(face.data, face.offset + i * VERTEX));
        corners.sort(Long::compareUnsigned);
        return new Key(List.copyOf(corners), face.data.getInt(face.offset + 28) & 0xffffff);
    }
    private static boolean tinted(Face face) {
        for (int i = 0; i < 4; i++) {
            int c = face.data.getInt(face.offset + i * VERTEX + 8);
            int r=c & 255, g=(c >>> 8) & 255, b=(c >>> 16) & 255;
            if (Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)) > 2) return true;
        }
        return false;
    }
    /** Buffers remain caller-owned. Limits are compacted to remove merged overlay quads. */
    public static int merge(List<ByteBuffer> buffers) {
        var groups = new HashMap<Key, List<Face>>();
        for (var data : buffers) {
            if (data.remaining() % QUAD != 0) throw new IllegalArgumentException("Incomplete terrain quad");
            for (int p=data.position();p<data.limit();p+=QUAD) {
                var face = new Face(data,p);
                groups.computeIfAbsent(key(face), ignored -> new ArrayList<>()).add(face);
            }
        }
        var removed = new IdentityHashMap<ByteBuffer, Set<Integer>>();
        int merged=0;
        for (var group : groups.values()) {
            // A conservative rule: exactly one achromatic base and one tinted overlay.
            // Do not guess ordering for arbitrary coincident translucent materials.
            if (group.size()!=2 || tinted(group.get(0))==tinted(group.get(1))) continue;
            var overlay=tinted(group.get(0))?group.get(0):group.get(1);
            var base=overlay==group.get(0)?group.get(1):group.get(0);
            boolean differentUV=false;
            int[] mapping=new int[4];
            for(int i=0;i<4;i++) {
                int dst=base.offset+i*VERTEX;
                for(int j=0;j<4;j++) if(corner(base.data,dst)==corner(overlay.data,overlay.offset+j*VERTEX)) {
                    mapping[i]=overlay.offset+j*VERTEX;
                    differentUV |= base.data.getInt(dst+12)!=overlay.data.getInt(mapping[i]+12);
                    break;
                }
            }
            if (!differentUV) continue;
            for(int i=0;i<4;i++) {
                int dst=base.offset+i*VERTEX, src=mapping[i];
                base.data.putInt(dst+40,overlay.data.getInt(src+12)); // overlay UV
                base.data.putInt(dst+44,overlay.data.getInt(src+8));  // overlay tint
                base.data.putInt(dst+48,1); // layer present
            }
            removed.computeIfAbsent(overlay.data, ignored -> new HashSet<>()).add(overlay.offset);
            merged++;
        }
        for(var data:buffers) {
            var omitted=removed.get(data);
            if(omitted==null) continue;
            int write=data.position();
            for(int read=data.position();read<data.limit();read+=QUAD) if(!omitted.contains(read)) {
                if(write!=read) for(int offset=0;offset<QUAD;offset+=4) data.putInt(write+offset,data.getInt(read+offset));
                write+=QUAD;
            }
            data.limit(write);
        }
        return merged;
    }
}
