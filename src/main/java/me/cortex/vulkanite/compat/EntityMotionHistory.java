package me.cortex.vulkanite.compat;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Per-entity/material correspondence, never the concatenated scene vertex order. */
public final class EntityMotionHistory {
    private record Snapshot(float[] positions, int[] signature) {}
    private Map<Object,Snapshot> previous=new HashMap<>(), current=new HashMap<>();
    private Object world;
    public void begin(Object world) {
        if(this.world!=world) previous.clear();
        this.world=world; current.clear();
    }
    public void apply(Object identity, ByteBuffer vertices) {
        int count=vertices.remaining()/EntityFrame.STRIDE;
        float[] positions=new float[count*3]; int[] signature=new int[count*2];
        for(int v=0;v<count;v++) {
            int base=vertices.position()+v*EntityFrame.STRIDE;
            for(int axis=0;axis<3;axis++) positions[v*3+axis]=vertices.getFloat(base+axis*4);
            signature[v*2]=vertices.getInt(base+16); signature[v*2+1]=vertices.getInt(base+20);
        }
        var old=previous.get(identity);
        boolean valid=old!=null && java.util.Arrays.equals(signature,old.signature);
        for(int v=0;v<count;v++) {
            int base=vertices.position()+v*EntityFrame.STRIDE;
            for(int axis=0;axis<3;axis++) vertices.putFloat(base+32+axis*4,valid?old.positions[v*3+axis]:positions[v*3+axis]);
            vertices.putInt(base+44,valid?1:0);
        }
        if(current.put(identity,new Snapshot(positions,signature))!=null) throw new IllegalStateException("Duplicate entity capture identity");
    }
    public void end() { var old=previous; previous=current; current=old; current.clear(); }
    public void clear() { previous.clear(); current.clear(); world=null; }
}
