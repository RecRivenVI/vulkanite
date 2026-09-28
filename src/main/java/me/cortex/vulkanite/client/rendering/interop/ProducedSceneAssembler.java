package me.cortex.vulkanite.client.rendering.interop;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import me.cortex.vulkanite.client.rendering.FrameSnapshot;
import me.cortex.vulkanite.compat.EntityFrame;
import me.cortex.vulkanite.compat.EntityVertexCompatibility;
import me.cortex.vulkanite.compat.IrisEntityVertexIds;
import me.cortex.vulkanite.compat.HandProjectionMapping;
import me.cortex.vulkanite.lib.memory.VGImage;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Converts adapter-owned normal Draw copies into the versioned neutral scene ABI. */
public final class ProducedSceneAssembler implements AutoCloseable {
    private static final int MAX_SCENE_BYTES = 512 << 20;
    private record Piece(ProducedDraws.Draw draw, int firstVertex, int vertexCount,
                         int materialId, int flags) {}
    private record PublicKey(int vertexHandle, int indexHandle, int firstIndex,
                             int indexCount, int baseVertex, int vertexHash, int indexHash,
                             EntityFrame.MaterialTextures textures) {}

    private ByteBuffer vertices = MemoryUtil.memAlloc(1 << 20).order(ByteOrder.nativeOrder());

    /**
     * The returned EntityFrame borrows this assembler's buffer. The core must
     * finish its synchronous upload before begin of the next producer frame.
     */
    public EntityFrame assemble(ProducedDraws.Frame produced,
                                List<PublicIndexedDrawCapture.Draw> indexed, FrameSnapshot snapshot) {
        long assembleStart = System.nanoTime();
        try {
            return assemble0(produced, indexed, snapshot);
        } finally {
            me.cortex.vulkanite.audit.Diagnostics.addCpu("assemble", System.nanoTime() - assembleStart);
        }
    }

    private EntityFrame assemble0(ProducedDraws.Frame produced,
                                  List<PublicIndexedDrawCapture.Draw> indexed, FrameSnapshot snapshot) {
        if (produced.failure() != null)
            throw new IllegalStateException("Normal producer copy failed after Iris upload", produced.failure());
        if (snapshot == null) throw new IllegalArgumentException("Missing scene frame");
        var projection=snapshot.gbufferProjection();
        if (!projection.isFinite() || !snapshot.gbufferModelView().isFinite()
                || Math.abs(projection.m00())<=0.0001f) {
            // Iris's first composite can precede a usable current camera projection.
            // Do not promote invalid hand coordinates to a world-space BLAS.
            return null;
        }
        var mainEntities = new HashSet<UUID>();
        var mainBlockEntities = new HashSet<Long>();
        for (var draw:produced.draws()) if (draw.pass()==ProducedDraws.Pass.MAIN
                && draw.ranges().complete()) for (var span:draw.ranges().spans()) {
            if (span.kind()==DynamicSubmitOrigins.Kind.SURFACE && span.origin()!=null) {
                if(span.origin().entityId()!=null) mainEntities.add(span.origin().entityId());
                if(span.origin().blockEntityPos()!=null)
                    mainBlockEntities.add(span.origin().blockEntityPos());
            }
        }

        var pieces=new ArrayList<Piece>();
        for (var draw:produced.draws()) {
            if (draw.vertexCount()==0) continue;
            if (draw.topology()!=PrimitiveTopology.QUADS) continue;
            if (draw.vertexCount()%4!=0)
                throw new IllegalStateException("Normal producer quad Draw has incomplete vertices");
            if (draw.input().renderTypeName().toLowerCase(Locale.ROOT).contains("glint")) continue;
            if (draw.input().textures()==null) {
                // Untextured debug/outline draws are not physical material surfaces.
                continue;
            }
            List<ProducedSubmitRanges.Span> spans = draw.ranges().complete()
                    ? draw.ranges().spans()
                    : List.of(new ProducedSubmitRanges.Span(0,draw.vertexCount(),null,
                            DynamicSubmitOrigins.Kind.SURFACE));
            if (spans.isEmpty() && draw.input().particle())
                spans=List.of(new ProducedSubmitRanges.Span(0,draw.vertexCount(),null,
                        DynamicSubmitOrigins.Kind.SURFACE));
            if (spans.isEmpty() && draw.vertexCount()>0)
                throw new IllegalStateException("Complete nonempty producer Draw has no node spans");
            var source=draw.vertices();
            for (var span:spans) {
                int first=span.start(),end=span.end();
                if (first<0 || end>draw.vertexCount() || first>end || first%4!=0 || end%4!=0)
                    throw new IllegalStateException("Invalid normal producer submit range " + first + ".." + end);
                if (decorative(span.kind())) continue;
                var id=span.origin()==null?null:span.origin().entityId();
                var blockPos=span.origin()==null?null:span.origin().blockEntityPos();
                if (draw.pass()==ProducedDraws.Pass.SHADOW) {
                    if (id==null && blockPos==null) continue;
                    if ((id!=null && mainEntities.contains(id))
                            || (blockPos!=null && mainBlockEntities.contains(blockPos))) continue;
                }
                int flags=draw.input().twoSided()?EntityFrame.TWO_SIDED:0;
                if (draw.input().particle()) flags|=EntityFrame.PARTICLE;
                if (draw.input().alphaCutout()) flags|=EntityFrame.ALPHA_CUTOUT;
                if (draw.input().alphaBlend()) flags|=EntityFrame.ALPHA_BLEND;
                if (draw.pass()==ProducedDraws.Pass.HAND) flags|=EntityFrame.VIEW_MODEL;
                if (span.origin()!=null && span.origin().cameraBody()) flags|=EntityFrame.CAMERA_HIDDEN;
                for(int vertex=first;vertex<end;) {
                    int material=materialAtQuad(draw,source,vertex,span.origin());
                    int stop=vertex+4;
                    while(stop<end && materialAtQuad(draw,source,stop,span.origin())==material) stop+=4;
                    pieces.add(new Piece(draw,vertex,stop-vertex,material,flags));
                    vertex=stop;
                }
            }
        }

        var selectedIndexed = selectIndexed(indexed);
        int required=0;
        for(var piece:pieces) required=Math.addExact(required,
                Math.multiplyExact(Math.multiplyExact(piece.vertexCount()/4,6),EntityFrame.STRIDE));
        for (var draw : selectedIndexed)
            required = Math.addExact(required, Math.multiplyExact(draw.indexCount(), EntityFrame.STRIDE));
        if (required > MAX_SCENE_BYTES)
            throw new IllegalStateException("Neutral scene exceeds " + MAX_SCENE_BYTES + " vertex bytes");
        ensureCapacity(required);
        vertices.clear();
        var textures=new ArrayList<EntityFrame.MaterialTextures>();
        for(var piece:pieces) {
                int texture=textureSlot(textures,piece.draw().input().textures());
                int byteStart=vertices.position();
                var draw=piece.draw();
                int stride=draw.format().getVertexSize();
                var source=draw.vertices();
                source.position(piece.firstVertex()*stride)
                        .limit((piece.firstVertex()+piece.vertexCount())*stride);
                EntityVertexCompatibility.append(source.slice().order(ByteOrder.nativeOrder()),
                        draw.format(),texture,vertices);
                int byteEnd=vertices.position();
                var written=region(byteStart,byteEnd);
                transform(written,draw,snapshot);
                for(int base=0;base<written.limit();base+=EntityFrame.STRIDE) {
                    written.putInt(base+32,written.getInt(base+32)|piece.flags());
                    written.putInt(base+36,piece.materialId());
                }
        }
        for (var draw : selectedIndexed) appendIndexed(draw, textures);
        if (vertices.position()==0) return null;
        var output=vertices.duplicate().order(ByteOrder.nativeOrder());
        output.flip();
        me.cortex.vulkanite.audit.Diagnostics.addAssembled(output.remaining() / EntityFrame.STRIDE);
        return EntityFrame.borrowed(output.slice().order(ByteOrder.nativeOrder()),List.copyOf(textures));
    }

    private static boolean decorative(DynamicSubmitOrigins.Kind kind) {
        return kind==DynamicSubmitOrigins.Kind.SHADOW_PATCH
                || kind==DynamicSubmitOrigins.Kind.NAME_TAG
                || kind==DynamicSubmitOrigins.Kind.OUTLINE
                || kind==DynamicSubmitOrigins.Kind.GIZMO
                || kind==DynamicSubmitOrigins.Kind.BREAKING_OVERLAY;
    }

    private int materialAtQuad(ProducedDraws.Draw draw,ByteBuffer source,int vertex,
                               DynamicSubmitOrigins.Origin origin) {
        int stride=draw.format().getVertexSize();
        int value=materialAtVertex(draw,source,vertex*stride,origin);
        for(int i=1;i<4;i++) if(materialAtVertex(draw,source,(vertex+i)*stride,origin)!=value)
            throw new IllegalStateException("Iris material ID changes inside one physical quad");
        return value;
    }

    private int materialAtVertex(ProducedDraws.Draw draw,ByteBuffer source,int offset,
                                 DynamicSubmitOrigins.Origin origin) {
        if (draw.input().particle()) return 0;
        var ids=IrisEntityVertexIds.decode(draw.format(),source,offset);
        var category=origin!=null && origin.itemSurface()
                ? MaterialContextSelection.Category.ITEM
                : origin!=null && origin.blockEntityPos()!=null
                ? MaterialContextSelection.Category.BLOCK_ENTITY
                : origin!=null && origin.entityId()!=null
                ? MaterialContextSelection.Category.ENTITY
                : MaterialContextSelection.Category.UNKNOWN;
        int producerId=category==MaterialContextSelection.Category.ITEM
                && origin.computedItemId()!=null?origin.computedItemId():-1;
        return MaterialContextSelection.select(ids.orElse(null),category,producerId);
    }

    private static int textureSlot(List<EntityFrame.MaterialTextures> textures,
                                   EntityFrame.MaterialTextures value) {
        int slot=textures.indexOf(value);
        if(slot<0) {slot=textures.size();textures.add(value);}
        if(slot>=EntityFrame.MAX_TEXTURES)
            throw new IllegalStateException("Normal producer scene texture limit exceeded");
        return slot;
    }

    /** Prefer a main-view copy; retain surfaces submitted only to Iris's shadow pass. */
    private static List<PublicIndexedDrawCapture.Draw> selectIndexed(
            List<PublicIndexedDrawCapture.Draw> source) {
        var main = new HashMap<PublicKey, List<PublicIndexedDrawCapture.Draw>>();
        var vertexHashes = new IdentityHashMap<byte[], Integer>();
        var selected = new ArrayList<PublicIndexedDrawCapture.Draw>(source.size());
        for (var draw : source) if (draw.pass() == ProducedDraws.Pass.MAIN) {
            selected.add(draw);
            main.computeIfAbsent(publicKey(draw, vertexHashes), ignored -> new ArrayList<>()).add(draw);
        }
        for (var draw : source) if (draw.pass() == ProducedDraws.Pass.SHADOW) {
            boolean seenInMain = false;
            var candidates = main.get(publicKey(draw, vertexHashes));
            if (candidates != null) for (int i = 0; i < candidates.size(); i++)
                if (sameWorldPlacement(candidates.get(i), draw)) {
                    candidates.remove(i); // One main submission can retire only one shadow submission.
                    seenInMain = true;
                    break;
                }
            if (!seenInMain) selected.add(draw);
        }
        return selected;
    }

    private static PublicKey publicKey(PublicIndexedDrawCapture.Draw draw,
                                       IdentityHashMap<byte[], Integer> vertexHashes) {
        int vertexHash = vertexHashes.computeIfAbsent(draw.vertexBytes(), Arrays::hashCode);
        return new PublicKey(draw.sourceVertexHandle(), draw.sourceIndexHandle(), draw.firstIndex(),
                draw.indexCount(), draw.baseVertex(), vertexHash,
                Arrays.hashCode(draw.indexBytes()), draw.textures());
    }

    private static boolean sameWorldPlacement(PublicIndexedDrawCapture.Draw a,
                                              PublicIndexedDrawCapture.Draw b) {
        Matrix4f first = worldFromLocal(a), second = worldFromLocal(b);
        if (!first.isFinite() || !second.isFinite()) return false;
        if (a.cameraOrigin().distanceSquared(b.cameraOrigin()) > 1.0e-12) return false;
        float[] left = new float[16], right = new float[16];
        first.get(left); second.get(right);
        for (int component = 0; component < left.length; component++) {
            float tolerance = Math.max(1.0e-4f,
                    8f * Math.max(Math.ulp(left[component]), Math.ulp(right[component])));
            if (Math.abs(left[component] - right[component]) > tolerance) return false;
        }
        return true;
    }

    private static Matrix4f worldFromLocal(PublicIndexedDrawCapture.Draw draw) {
        return new Matrix4f(draw.passView()).invert().mul(matrixAt(draw.transform(), 0));
    }

    /** Public draw snapshots are heap byte arrays; JOML's ByteBuffer path expects native memory. */
    private static Matrix4f matrixAt(ByteBuffer bytes, int offset) {
        if (offset < 0 || bytes.limit() - offset < 16 * Float.BYTES)
            throw new IllegalArgumentException("Public draw matrix exceeds its owned snapshot");
        float[] values = new float[16];
        for (int i = 0; i < values.length; i++) values[i] = bytes.getFloat(offset + i * Float.BYTES);
        return new Matrix4f().set(values);
    }

    /** Expands the producer's exact indexed triangle order into owned world vertices. */
    private void appendIndexed(PublicIndexedDrawCapture.Draw draw,
                               List<EntityFrame.MaterialTextures> textures) {
        int texture = textureSlot(textures, draw.textures());
        var layout = draw.layout();
        ByteBuffer source = draw.vertices();
        ByteBuffer indices = draw.indices();
        ByteBuffer uniform = draw.transform();
        Matrix4f modelView = matrixAt(uniform, 0);
        Matrix4f textureMatrix = matrixAt(uniform, 64);
        if (!modelView.isFinite() || !textureMatrix.isFinite() || !draw.passView().isFinite())
            throw new IllegalStateException("Public draw has a non-finite transform");
        Matrix4f worldFromLocal = worldFromLocal(draw);
        Matrix3f normalFromLocal = worldFromLocal.normal(new Matrix3f());
        Matrix3f tangentFromLocal = new Matrix3f(worldFromLocal);
        if (!worldFromLocal.isFinite() || !normalFromLocal.isFinite())
            throw new IllegalStateException("Public draw transform cannot be inverted");
        float handedness = Math.signum(tangentFromLocal.determinant());
        if (handedness == 0 || !Float.isFinite(handedness))
            throw new IllegalStateException("Public draw has a degenerate tangent transform");
        var constants = draw.constants()==null
                ? new PublicIndexedDrawCapture.Constants(new byte[EntityFrame.STRIDE],0)
                : draw.constants();
        ByteBuffer defaultAttributes=ByteBuffer.wrap(constants.bytes()).order(ByteOrder.nativeOrder());
        int presence=constants.presence();
        var camera = draw.cameraOrigin();
        float colorR=uniform.getFloat(128), colorG=uniform.getFloat(132);
        float colorB=uniform.getFloat(136), colorA=uniform.getFloat(140);
        float offsetX=uniform.getFloat(144), offsetY=uniform.getFloat(148), offsetZ=uniform.getFloat(152);
        if (!Float.isFinite(colorR) || !Float.isFinite(colorG) || !Float.isFinite(colorB)
                || !Float.isFinite(colorA) || !Float.isFinite(offsetX)
                || !Float.isFinite(offsetY) || !Float.isFinite(offsetZ))
            throw new IllegalStateException("Public draw material/position uniform is non-finite");
        var position = new Vector3f();
        var normal = new Vector3f();
        var tangent = new Vector3f();
        var transformedUv = new Vector4f();
        int flags = (draw.twoSided() ? EntityFrame.TWO_SIDED : 0)
                | (draw.alphaBlend() ? EntityFrame.ALPHA_BLEND : EntityFrame.ALPHA_CUTOUT);
        for (int element = 0; element < draw.indexCount(); element++) {
            long index = draw.indexBytesPerElement() == Short.BYTES
                    ? Short.toUnsignedInt(indices.getShort(element * Short.BYTES))
                    : Integer.toUnsignedLong(indices.getInt(element * Integer.BYTES));
            long vertexIndex = index + draw.baseVertex();
            long byteOffset = vertexIndex * layout.stride();
            if (vertexIndex < 0 || byteOffset < 0 || byteOffset + layout.stride() > source.limit())
                throw new IllegalStateException("Public draw index " + index + " with baseVertex "
                        + draw.baseVertex() + " exceeds its captured vertex buffer");
            int base = Math.toIntExact(byteOffset);
            position.set(source.getFloat(base + layout.position()),
                    source.getFloat(base + layout.position() + 4),
                    source.getFloat(base + layout.position() + 8));
            if (!position.isFinite()) throw new IllegalStateException("Public draw vertex is non-finite");
            position.add(offsetX,offsetY,offsetZ);
            worldFromLocal.transformPosition(position);
            vertices.putFloat((float) (camera.x() + position.x));
            vertices.putFloat((float) (camera.y() + position.y));
            vertices.putFloat((float) (camera.z() + position.z));
            int packedColor = layout.color() >= 0 ? source.getInt(base + layout.color())
                    : (presence&EntityFrame.COLOR_PRESENT)!=0 ? defaultAttributes.getInt(12) : -1;
            vertices.putInt(modulateColor(packedColor,colorR,colorG,colorB,colorA));
            transformedUv.set(source.getFloat(base + layout.uv()),
                    source.getFloat(base + layout.uv() + 4),0,1);
            textureMatrix.transform(transformedUv);
            if (!transformedUv.isFinite()) throw new IllegalStateException("Public draw UV is non-finite");
            vertices.putFloat(transformedUv.x);
            vertices.putFloat(transformedUv.y);
            int packedNormal = 0;
            normal.zero();
            if ((presence&EntityFrame.NORMAL_PRESENT)!=0) {
                int packed = layout.normal() >= 0 ? source.getInt(base + layout.normal())
                        : defaultAttributes.getInt(24);
                normal.set((byte) packed / 127f, (byte) (packed >>> 8) / 127f,
                        (byte) (packed >>> 16) / 127f);
                if (normal.lengthSquared() > 1.0e-6f) {
                    normalFromLocal.transform(normal).normalize();
                    packedNormal = packNormal(normal.x) | (packNormal(normal.y) << 8)
                            | (packNormal(normal.z) << 16) | (packed&0xff000000);
                }
            }
            vertices.putInt(packedNormal);
            vertices.putInt(texture);
            vertices.putInt(flags);
            int uv1x=layout.uv1()>=0?source.getShort(base+layout.uv1()):defaultAttributes.getInt(40);
            int uv1y=layout.uv1()>=0?source.getShort(base+layout.uv1()+2):defaultAttributes.getInt(44);
            int uv2x=layout.uv2()>=0?source.getShort(base+layout.uv2()):defaultAttributes.getInt(48);
            int uv2y=layout.uv2()>=0?source.getShort(base+layout.uv2()+2):defaultAttributes.getInt(52);
            float midU=layout.midUv()>=0?source.getFloat(base+layout.midUv()):defaultAttributes.getFloat(56);
            float midV=layout.midUv()>=0?source.getFloat(base+layout.midUv()+4):defaultAttributes.getFloat(60);
            if ((presence&EntityFrame.MID_TEXCOORD_PRESENT)!=0
                    && (!Float.isFinite(midU)||!Float.isFinite(midV)))
                throw new IllegalStateException("Public draw mid UV is not finite");
            int packedTangent=layout.tangent()>=0?source.getInt(base+layout.tangent()):defaultAttributes.getInt(64);
            if ((presence&EntityFrame.TANGENT_PRESENT)!=0) {
                tangent.set((byte)packedTangent/127f,(byte)(packedTangent>>>8)/127f,
                        (byte)(packedTangent>>>16)/127f);
                if (tangent.lengthSquared()>1.0e-6f) {
                    tangentFromLocal.transform(tangent);
                    if (normal.lengthSquared()>1.0e-6f)
                        tangent.fma(-tangent.dot(normal),normal);
                    if (tangent.lengthSquared()<1.0e-10f)
                        throw new IllegalStateException("Public draw tangent collapsed after world transform");
                    tangent.normalize();
                    int tangentW=(byte)(packedTangent>>>24);
                    if (handedness<0) tangentW=-tangentW;
                    packedTangent=packNormal(tangent.x)|(packNormal(tangent.y)<<8)
                            |(packNormal(tangent.z)<<16)|((tangentW&255)<<24);
                }
            }
            int[] irisIds=new int[4];
            for(int channel=0;channel<4;channel++) irisIds[channel]=layout.irisEntity()>=0
                    ? Short.toUnsignedInt(source.getShort(base+layout.irisEntity()+channel*2))
                    : defaultAttributes.getInt(68+channel*4);
            int mcX=layout.mcEntity()>=0?source.getShort(base+layout.mcEntity()):defaultAttributes.getInt(84);
            int mcY=layout.mcEntity()>=0?source.getShort(base+layout.mcEntity()+2):defaultAttributes.getInt(88);
            int midBlock=layout.midBlock()>=0?source.getInt(base+layout.midBlock()):defaultAttributes.getInt(92);
            vertices.putInt(publicMaterial(irisIds,presence));
            vertices.putInt(uv1x).putInt(uv1y).putInt(uv2x).putInt(uv2y);
            vertices.putFloat(midU).putFloat(midV);
            vertices.putInt(packedTangent);
            for(int id:irisIds) vertices.putInt(id);
            vertices.putInt(mcX).putInt(mcY).putInt(midBlock).putInt(presence);
            vertices.putInt(0).putInt(0).putInt(0);
        }
    }

    private static int publicMaterial(int[] raw,int presence) {
        if ((presence&EntityFrame.IRIS_ENTITY_PRESENT)==0) return 0;
        int selected=0;
        for(int channel=0;channel<3;channel++) {
            int id=raw[channel];
            if(id<=0 || id==0xffff) continue;
            if(selected!=0 && selected!=id) return 0; // Ambiguous context; raw channels stay available.
            selected=id;
        }
        return selected;
    }

    private static int modulateColor(int color, float r, float g, float b, float a) {
        return channel(color,0,r) | (channel(color,8,g)<<8)
                | (channel(color,16,b)<<16) | (channel(color,24,a)<<24);
    }

    private static int channel(int color, int shift, float multiplier) {
        return Math.clamp(Math.round(((color>>>shift)&255)*multiplier),0,255);
    }

    private ByteBuffer region(int start,int end) {
        var slice=vertices.duplicate().order(ByteOrder.nativeOrder());
        slice.position(start).limit(end);
        return slice.slice().order(ByteOrder.nativeOrder());
    }

    private static void transform(ByteBuffer written,ProducedDraws.Draw draw,FrameSnapshot snapshot) {
        var camera=snapshot.cameraPosition();
        var position=new Vector3f();
        var normal=new Vector3f();
        var tangent=new Vector3f();
        HandProjectionMapping hand = draw.pass()==ProducedDraws.Pass.HAND
                ? new HandProjectionMapping(draw.handProjection(),snapshot):null;
        Matrix3f shadowNormal=draw.pass()==ProducedDraws.Pass.SHADOW
                ? draw.shadowInverse().normal(new Matrix3f()):null;
        Matrix3f shadowTangent=draw.pass()==ProducedDraws.Pass.SHADOW
                ? new Matrix3f(draw.shadowInverse()):null;
        for(int base=0;base<written.limit();base+=EntityFrame.STRIDE) {
            position.set(written.getFloat(base),written.getFloat(base+4),written.getFloat(base+8));
            float rawX=position.x,rawY=position.y,rawZ=position.z;
            if(draw.pass()==ProducedDraws.Pass.SHADOW)
                draw.shadowInverse().transformPosition(position);
            if(hand!=null) {
                hand.position(rawX,rawY,rawZ,position);
                written.putFloat(base,position.x);
                written.putFloat(base+4,position.y);
                written.putFloat(base+8,position.z);
            } else {
                written.putFloat(base,(float)(camera.x()+position.x));
                written.putFloat(base+4,(float)(camera.y()+position.y));
                written.putFloat(base+8,(float)(camera.z()+position.z));
            }
            if(draw.pass()==ProducedDraws.Pass.SHADOW || hand!=null) {
                int presence=written.getInt(base+96);
                int packed=written.getInt(base+24);
                normal.set((byte)packed/127f,(byte)(packed>>>8)/127f,
                        (byte)(packed>>>16)/127f);
                if((presence&EntityFrame.NORMAL_PRESENT)!=0 && normal.lengthSquared()>1.0e-6f) {
                    if(hand!=null) hand.normal(rawX,rawY,rawZ,
                            normal.x,normal.y,normal.z,normal);
                    else shadowNormal.transform(normal).normalize();
                    written.putInt(base+24,packNormal(normal.x)
                            |(packNormal(normal.y)<<8)|(packNormal(normal.z)<<16)
                            |(packed&0xff000000));
                } else normal.zero();
                if((presence&EntityFrame.TANGENT_PRESENT)!=0) {
                    int packedTangent=written.getInt(base+64);
                    tangent.set((byte)packedTangent/127f,(byte)(packedTangent>>>8)/127f,
                            (byte)(packedTangent>>>16)/127f);
                    if(tangent.lengthSquared()>1.0e-6f) {
                        float orientation;
                        if(hand!=null) orientation=hand.tangent(rawX,rawY,rawZ,
                                tangent.x,tangent.y,tangent.z,tangent);
                        else {shadowTangent.transform(tangent);orientation=Math.signum(shadowTangent.determinant());}
                        if(normal.lengthSquared()>1.0e-6f)
                            tangent.fma(-tangent.dot(normal),normal);
                        if(!tangent.isFinite() || tangent.lengthSquared()<1.0e-10f) {
                            // Degenerate producer tangent after world transform: keep the
                            // vertex and drop the optional field instead of failing the frame.
                            me.cortex.vulkanite.audit.Diagnostics.onTangentFallback();
                            written.putInt(base+64,0);
                            written.putInt(base+96,presence&~EntityFrame.TANGENT_PRESENT);
                        } else {
                            tangent.normalize();
                            int w=(byte)(packedTangent>>>24);
                            if(orientation<0) w=-w;
                            written.putInt(base+64,packNormal(tangent.x)
                                    |(packNormal(tangent.y)<<8)|(packNormal(tangent.z)<<16)
                                    |((w&255)<<24));
                        }
                    }
                }
            }
        }
    }

    private static int packNormal(float value) {
        return Math.round(Math.max(-1f,Math.min(1f,value))*127f)&255;
    }

    private void ensureCapacity(int required) {
        if(required<=vertices.capacity()) return;
        int capacity=vertices.capacity();
        while(capacity<required) capacity=Math.multiplyExact(capacity,2);
        MemoryUtil.memFree(vertices);
        vertices=MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
    }

    @Override public void close() {
        if(vertices!=null) MemoryUtil.memFree(vertices);
        vertices=null;
    }
}
