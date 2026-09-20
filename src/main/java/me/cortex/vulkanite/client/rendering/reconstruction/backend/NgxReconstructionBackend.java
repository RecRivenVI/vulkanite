package me.cortex.vulkanite.client.rendering.reconstruction.backend;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.client.rendering.dlss.NgxBridge;
import me.cortex.vulkanite.client.rendering.reconstruction.ReconstructionMath;
import me.cortex.vulkanite.client.rendering.reconstruction.ReconstructionRequest;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.vulkan.VkImageBlit;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Owned per pack pipeline; all mutation/retirement occurs after the existing frame fence. */
public final class NgxReconstructionBackend implements AutoCloseable {
    private static final int[] FORMATS = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT,
            VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT, VK_FORMAT_R8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT};
    private final VContext ctx;
    public final ReconstructionRequest request;
    private final VImage[] images = new VImage[9];
    private final VImageView[] views = new VImageView[9];
    private final long[] descriptors = new long[45];
    private final float[] constants = new float[37];
    private float preExposure=1;
    private final Matrix4f previousViewProjection = new Matrix4f(), previousProjection = new Matrix4f();
    private final Vector3f previousPosition = new Vector3f();
    private Object level, cameraEntity;
    private long session, lastTime, frames;
    private int width, height, outputWidth, outputHeight;
    private boolean history, failed;

    public NgxReconstructionBackend(VContext ctx, ReconstructionRequest request) { this.ctx=ctx; this.request=request; }
    public int width() { return width; }
    public int height() { return height; }
    public boolean enabled() { return session != 0 && !failed; }

    /** Read only after the existing GL completion boundary, before Vulkan acquires shared textures. */
    public void readExposure(me.cortex.vulkanite.lib.memory.VGImage meter) {
        if(meter.width!=1 || meter.height!=1 || meter.format!=VK_FORMAT_R32G32B32A32_SFLOAT)
            throw new IllegalArgumentException("Exposure target must be 1x1 RGBA32F");
        try(var stack=stackPush()) {
            var value=stack.mallocFloat(4);
            org.lwjgl.opengl.GL45C.glGetTextureSubImage(meter.glId,0,0,0,0,1,1,1,
                    org.lwjgl.opengl.GL11C.GL_RGBA,org.lwjgl.opengl.GL11C.GL_FLOAT,value);
            float ev=value.get(0);
            preExposure=value.get(3)==1 && Float.isFinite(ev)?(float)Math.pow(2,Math.clamp(ev,-8,24)):1;
        }
    }

    public void prepare(int outWidth, int outHeight) {
        if (images[0] != null && outputWidth==outWidth && outputHeight==outHeight && !(failed && session!=0)) return;
        close();
        outputWidth=width=outWidth; outputHeight=height=outHeight;
        int mode=Integer.getInteger("vulkanite.dlss.mode",request.mode());
        int quality=Integer.getInteger("vulkanite.dlss.quality",request.quality());
        if (mode!=0 && !failed && NgxBridge.available()) {
            try {
                var physical=ctx.device.getPhysicalDevice();
                session=NgxBridge.open(physical.getInstance().address(),physical.address(),ctx.device.address(),NgxBridge.DIRECTORY.toString(),mode,quality,outWidth,outHeight);
                int[] size=NgxBridge.dimensions(session); width=size[0]; height=size[1];
                if (width<1 || height<1 || width>outWidth || height>outHeight) throw new IllegalStateException("Invalid NGX input extent");
                RuntimeException[] failure={null};
                ctx.cmd.executeWait(cmd -> {
                    try { NgxBridge.create(session,cmd.address()); }
                    catch (RuntimeException error) { failure[0]=error; }
                });
                if (failure[0]!=null) throw failure[0];
                LoggerFactory.getLogger("Vulkanite/DLSS").info("{} ready quality={} input={}x{} output={}x{}",mode==2?"RR":"SR",quality,width,height,outWidth,outHeight);
            } catch (RuntimeException error) {
                if (session!=0) { NgxBridge.close(session); session=0; }
                width=outWidth; height=outHeight; failed=true;
                LoggerFactory.getLogger("Vulkanite/DLSS").warn("Reconstruction unavailable; using full-resolution pack output",error);
            }
        }
        try {
            for (int i=0;i<9;i++) {
                int w=i==8?outWidth:width, h=i==8?outHeight:height;
                int format=request.version()==2 && (i==0 || i==8)?VK_FORMAT_R32G32B32A32_SFLOAT:FORMATS[i];
                images[i]=ctx.memory.createImage2D(w,h,1,format,VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                views[i]=new VImageView(ctx,images[i]);
                descriptors[i*5]=images[i].image(); descriptors[i*5+1]=views[i].view; descriptors[i*5+2]=format;
                descriptors[i*5+3]=w; descriptors[i*5+4]=h;
            }
            ctx.cmd.executeWait(cmd -> {
                for (var image:images) cmd.encodeImageTransition(image,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_ASPECT_COLOR_BIT,1);
            });
        } catch (RuntimeException error) { close(); throw error; }
    }

    public void bind(DescriptorUpdateBuilder update) {
        for (int i=0;i<8;i++) update.imageStore(8+i,views[i]);
    }

    public void writeCamera(ByteBuffer buffer, Matrix4f view, Matrix4f projection) {
        projection = ReconstructionMath.reverseZeroToOne(projection);
        long now=System.nanoTime();
        var mc=Minecraft.getInstance();
        var position=new Matrix4f(view).invert().getTranslation(new Vector3f());
        boolean reset=!history || level!=mc.level || cameraEntity!=mc.getCameraEntity() || position.distance(previousPosition)>32
                || ReconstructionMath.projectionCut(projection,previousProjection) || lastTime!=0 && now-lastTime>1_000_000_000L;
        if (reset) frames=0;
        var current=new Matrix4f(projection).mul(view);
        (reset?current:previousViewProjection).get(176,buffer);
        current.get(240,buffer); view.get(304,buffer); projection.get(368,buffer);
        float jitterX=enabled()?halton((int)(frames%32)+1,2)-0.5f:0;
        float jitterY=enabled()?halton((int)(frames%32)+1,3)-0.5f:0;
        buffer.putFloat(432,jitterX).putFloat(436,jitterY).putFloat(440,width).putFloat(444,height);
        buffer.putFloat(448,outputWidth).putFloat(452,outputHeight).putFloat(456,reset?1:0).putFloat(460,enabled()?1:0);
        buffer.putFloat(464,preExposure).putFloat(468,1/preExposure);
        constants[0]=-jitterX; constants[1]=-jitterY; constants[2]=reset?1:0;
        constants[3]=lastTime==0?16.67f:Math.clamp((now-lastTime)/1_000_000f,0.1f,1000f);
        constants[36]=preExposure;
        float[] matrix=new float[16]; view.get(matrix); System.arraycopy(matrix,0,constants,4,16);
        projection.get(matrix); System.arraycopy(matrix,0,constants,20,16);
        previousViewProjection.set(current); previousProjection.set(projection); previousPosition.set(position);
        level=mc.level; cameraEntity=mc.getCameraEntity(); lastTime=now; history=true; frames++;
    }

    private static float halton(int index,int base) {
        float result=0,fraction=1;
        while(index>0) { fraction/=base; result+=fraction*(index%base); index/=base; }
        return result;
    }

    public void execute(VCmdBuff cmd,VImage target) {
        VImage result=images[0];
        if (enabled()) {
            for(int i=0;i<8;i++) cmd.encodeImageTransition(images[i],VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_IMAGE_ASPECT_COLOR_BIT,1);
            try {
                NgxBridge.evaluate(session,cmd.address(),descriptors,constants);
                result=images[8];
                if(frames==1 || frames==120 || frames==600) LoggerFactory.getLogger("Vulkanite/DLSS").info("NGX evaluate succeeded frame={} reset={} preExposure={}",frames,constants[2]!=0,preExposure);
            } catch(RuntimeException error) {
                failed=true;
                LoggerFactory.getLogger("Vulkanite/DLSS").error("NGX evaluation failed; raw-color fallback, feature retired after frame completion",error);
            }
            for(int i=0;i<8;i++) cmd.encodeImageTransition(images[i],VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_ASPECT_COLOR_BIT,1);
        }
        cmd.encodeImageTransition(result,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_ASPECT_COLOR_BIT,1);
        cmd.encodeImageTransition(target,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_ASPECT_COLOR_BIT,1);
        try(var stack=stackPush()) {
            var blit=VkImageBlit.calloc(1,stack);
            blit.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                    .dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1));
            blit.get(0).srcOffsets(1).set(result.width,result.height,1);
            blit.get(0).dstOffsets(1).set(target.width,target.height,1);
            vkCmdBlitImage(cmd.buffer,result.image(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,target.image(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,blit,VK_FILTER_LINEAR);
        }
        cmd.encodeImageTransition(result,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_ASPECT_COLOR_BIT,1);
        cmd.encodeImageTransition(target,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_ASPECT_COLOR_BIT,1);
    }

    @Override public void close() {
        if(session!=0) { NgxBridge.close(session); session=0; }
        for(int i=0;i<9;i++) {
            if(views[i]!=null) { views[i].free(); views[i]=null; }
            if(images[i]!=null) { images[i].free(); images[i]=null; }
        }
        history=false; frames=0; lastTime=0;
    }
}

