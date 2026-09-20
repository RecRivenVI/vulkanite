package me.cortex.vulkanite.client.rendering;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import me.cortex.vulkanite.compat.*;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.mixin.minecraft.ParticleEngineAccessor;
import me.cortex.vulkanite.mixin.minecraft.ParticleGroupAccessor;
import me.cortex.vulkanite.mixin.iris.HandRendererAccessor;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.mixin.GameRendererAccessor;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState.HandRenderSelection;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;

/** Captures dynamic world geometry before ray dispatch; no lightmap or display color is baked in. */
public class EntityCapture implements AutoCloseable {
    public static final ThreadLocal<EntityCapture> ACTIVE = new ThreadLocal<>();
    private final Map<RenderType, ByteBufferBuilder> storage = new HashMap<>();
    private final Map<RenderType, BufferBuilder> builders = new HashMap<>();
    private final ByteBufferBuilder particleStorage = new ByteBufferBuilder(4096);
    private final QuadParticleRenderState particleState = new QuadParticleRenderState();
    private final EntityMotionHistory motion = new EntityMotionHistory();
    private final List<VGImage> textures = new ArrayList<>();
    private final List<ByteBuffer> chunks = new ArrayList<>();
    private record MotionKey(Object object, Object part, VGImage texture) {}
    private record HandKey(boolean main, Object item) {}
    private long frames;
    private int handQuads, particleQuads, bodyQuads;

    public EntityFrame capture(float delta, ClientLevel world) {
        var mc = Minecraft.getInstance();
        var dispatcher = mc.getEntityRenderDispatcher();
        var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        textures.clear(); chunks.clear();
        handQuads=particleQuads=bodyQuads=0;
        motion.begin(world);
        try {
            for (var entity : world.entitiesForRendering()) {
                if (entity.isRemoved() || entity.isSpectator()) continue;
                boolean cameraBody = entity == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
                var state = dispatcher.extractEntity(entity, delta);
                if (state.isInvisible) continue;
                state.shadowPieces.clear();
                state.nameTag = null;
                var nodes = new SubmitNodeStorage();
                dispatcher.submit(state, camera, state.x, state.y, state.z, new PoseStack(), nodes);
                captureNodes(nodes,entity.getUUID(),null,cameraBody?EntityFrame.CAMERA_HIDDEN:0,0);
            }
            captureHands(delta);
            captureParticles(delta);
            motion.end();
            if (++frames==1 || frames==120 || frames==600) {
                org.slf4j.LoggerFactory.getLogger("Vulkanite/Scene").info(
                    "PT geometry frame={}: cameraBody={} hand={} particle={} quads",frames,bodyQuads,handQuads,particleQuads);
            }
            if(chunks.isEmpty()) return null;
            int bytes=0;
            for(var chunk:chunks) bytes=Math.addExact(bytes,chunk.remaining());
            var vertices=MemoryUtil.memAlloc(bytes);
            try {
                for(var chunk:chunks) vertices.put(chunk);
                vertices.flip();
                return new EntityFrame(vertices,List.copyOf(textures));
            } catch(Throwable error) { MemoryUtil.memFree(vertices); throw error; }
        } catch(Throwable error) { motion.clear(); throw error; }
        finally {
            chunks.forEach(MemoryUtil::memFree); chunks.clear();
            resetBuilders();
        }
    }

    private void captureNodes(SubmitNodeStorage nodes,Object identity,Matrix4f transform,int flags,int atlasMaterial) {
        var features=(FeatureDispatcherAccess)Minecraft.getInstance().gameRenderer.featureRenderDispatcher();
        var context=features.vulkanite$context();
        ACTIVE.set(this);
        try {
            nodes.drainPhases(phase -> phase.sortInto((submit,ordered) -> {
                var renderer=features.vulkanite$renderers().getOrThrow(submit.featureType());
                if(renderer instanceof RenderTypeFeatureRenderer<?>)
                    ((FeatureRendererAccess)renderer).vulkanite$buildGroup(context,List.of(submit));
            }));
        } finally { ACTIVE.remove(); }
        try {
            for(var entry:builders.entrySet()) {
                var type=entry.getKey();
                try(var mesh=entry.getValue().build()) {
                    if(mesh==null || type.primitiveTopology()!=PrimitiveTopology.QUADS) continue;
                    // Enchantment glint is a screen-space decoration, not another physical surface.
                    if(type.toString().toLowerCase(Locale.ROOT).contains("glint")) continue;
                    var texture=type.prepare().textures().stream().filter(t->t.name().equals("Sampler0")).findFirst();
                    if(texture.isEmpty()) continue;
                    var image=shared(texture.get().textureView().texture());
                    int material=0;
                    if(atlasMaterial!=0) {
                        var tm=Minecraft.getInstance().getTextureManager();
                        if(image==shared(tm.getTexture(TextureAtlas.LOCATION_BLOCKS))
                                ||image==shared(tm.getTexture(TextureAtlas.LOCATION_ITEMS))) material=atlasMaterial;
                    }
                    append(mesh,image,new MotionKey(identity,type,image),transform,flags|(material<<16));
                }
            }
        } finally { resetBuilders(); }
    }

    private void captureHands(float delta) {
        var mc=Minecraft.getInstance();
        var game=mc.gameRenderer.gameRenderState();
        var camera=game.levelRenderState.cameraRenderState;
        var player=game.levelRenderState.playerRenderState;
        var state=player.firstPersonHandsAndItems;
        if(!player.hasPlayer || !mc.options.getCameraType().isFirstPerson() || camera.isPanoramicMode || mc.gameRenderer.mainCamera().isDetached()
                || camera.entityRenderState.isSleeping || game.guiRenderState.isHudHidden
                || mc.gameMode.getPlayerMode()==GameType.SPECTATOR || state.handRenderSelection==null) return;
        var bob=new PoseStack();
        var access=(GameRendererAccessor)mc.gameRenderer;
        access.invokeBobHurt(camera,bob);
        if(game.optionsRenderState.bobView) access.invokeBobView(camera,bob);
        var view=CapturedRenderingState.INSTANCE.getGbufferModelView();
        var projection=CapturedRenderingState.INSTANCE.getGbufferProjection();
        float aspect=(float)game.windowRenderState.width/game.windowRenderState.height;
        // Reproduce HUD angular size in the world's projection, without raster depth compression.
        var worldToView=new Matrix4f(view).translate(camera.pos.toVector3f().negate());
        var transform=ViewModelTransform.create(worldToView,projection,camera.hudFov,aspect,bob.last().pose());
        var selection=state.handRenderSelection;
        var handRenderer=(HandRendererAccessor)(Object)HandRenderer.INSTANCE;
        boolean wasSolid=handRenderer.vulkanite$isSolid();
        try {
            for(boolean main:new boolean[]{true,false}) {
                if(main?!selection.renderMainHand:!selection.renderOffHand) continue;
                state.handRenderSelection=main?HandRenderSelection.RENDER_MAIN_HAND_ONLY:HandRenderSelection.RENDER_OFF_HAND_ONLY;
                var item=main?state.mainHandItem:state.offHandItem;
                // Iris's injected submit filter also runs during our geometry-only capture.
                handRenderer.vulkanite$setSolid(!HandRenderer.INSTANCE.isHandTranslucent(item));
                var nodes=new SubmitNodeStorage();
                mc.gameRenderer.firstPersonHandsAndItemsRenderer.submitHandsWithItems(delta,new PoseStack(),nodes,player,state);
                captureNodes(nodes,new HandKey(main,item.getItem()),transform,EntityFrame.VIEW_MODEL,handMaterial(item));
            }
        } finally { state.handRenderSelection=selection; handRenderer.vulkanite$setSolid(wasSolid); }
    }

    private static int handMaterial(ItemStack item) {
        if(item.is(Items.GLASS)||item.is(Items.GLASS_PANE))return 101;
        if(item.is(Items.GLOWSTONE)||item.is(Items.SHROOMLIGHT)||item.is(Items.JACK_O_LANTERN)
                ||item.is(Items.TORCH)||item.is(Items.LANTERN))return 120;
        if(item.is(Items.SEA_LANTERN)||item.is(Items.SOUL_TORCH)||item.is(Items.SOUL_LANTERN))return 121;
        if(item.is(Items.REDSTONE_TORCH))return 122;
        return 0;
    }

    private void captureParticles(float delta) {
        var mc=Minecraft.getInstance();
        var camera=mc.gameRenderer.mainCamera();
        var translate=new Matrix4f().translation(camera.position().toVector3f());
        for(var group:((ParticleEngineAccessor)mc.particleEngine).vulkanite$groups().values()) {
            for(var particle:((ParticleGroupAccessor)group).vulkanite$particles()) {
                if(!particle.isAlive() || !(particle instanceof SingleQuadParticle quad))continue;
                particleState.clear();
                quad.extract(particleState,camera,delta);
                for(var layer:particleState.layers()) {
                    particleStorage.clear();
                    var builder=new BufferBuilder(particleStorage,PrimitiveTopology.QUADS,DefaultVertexFormat.PARTICLE);
                    particleState.buildLayer(layer,builder);
                    try(var mesh=builder.build()) {
                        if(mesh==null)continue;
                        var image=shared(mc.getTextureManager().getTexture(layer.textureAtlasLocation()));
                        int flags=EntityFrame.PARTICLE | (layer.translucent()?EntityFrame.ALPHA_COVERAGE:0);
                        append(mesh,image,new MotionKey(particle,layer,image),translate,flags|(particleMaterial(particle)<<16));
                    }
                }
            }
        }
        // Non-billboard particle render states (pickup models / elder guardian) submit entity geometry.
        var cameraState=mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        for(var group:mc.gameRenderer.gameRenderState().levelRenderState.particlesRenderState.particles) {
            if(group instanceof QuadParticleRenderState)continue;
            var nodes=new SubmitNodeStorage();group.submit(nodes,cameraState);
            captureNodes(nodes,group,translate,EntityFrame.PARTICLE|EntityFrame.ALPHA_COVERAGE,0);
        }
    }

    private static int particleMaterial(Particle particle) {
        // Explicit emitters; fullbright lightmap values are deliberately not an emission test.
        return switch(particle.getClass().getSimpleName()) {
            case "FlameParticle", "SmallFlameParticle", "LavaParticle", "FireflyParticle", "GlowParticle" -> 120;
            case "SoulParticle" -> 121;
            default -> 0;
        };
    }

    private static VGImage shared(Object texture) {
        if(texture instanceof IVGImage shared && shared.getVGImage()!=null)return shared.getVGImage();
        throw new IllegalStateException("Dynamic scene texture is not shared with Vulkan: "+texture);
    }

    private void append(MeshData mesh,VGImage image,Object identity,Matrix4f transform,int flags) {
        int id=textures.indexOf(image);
        if(id<0){id=textures.size();textures.add(image);}
        if(id>=EntityFrame.MAX_TEXTURES)throw new IllegalStateException("Dynamic scene texture limit exceeded");
        int count=mesh.vertexBuffer().remaining()/mesh.drawState().format().getVertexSize();
        var chunk=MemoryUtil.memAlloc(Math.multiplyExact(count,EntityFrame.STRIDE));
        chunks.add(chunk);
        EntityVertexCompatibility.append(mesh.vertexBuffer(),mesh.drawState().format(),id,chunk);chunk.flip();
        EntityVertexCompatibility.transform(chunk,transform);
        motion.apply(identity,chunk);
        for(int vertex=0;vertex<chunk.limit();vertex+=EntityFrame.STRIDE)
            chunk.putInt(vertex+44,chunk.getInt(vertex+44)|flags);
        if((flags&EntityFrame.VIEW_MODEL)!=0)handQuads+=count/4;
        if((flags&EntityFrame.PARTICLE)!=0)particleQuads+=count/4;
        if((flags&EntityFrame.CAMERA_HIDDEN)!=0)bodyQuads+=count/4;
    }

    private void resetBuilders(){builders.clear();storage.values().forEach(ByteBufferBuilder::clear);}
    public VertexConsumer getBuffer(RenderType type) {
        return builders.computeIfAbsent(type,key->new BufferBuilder(storage.computeIfAbsent(key,ignored->new ByteBufferBuilder(4096)),key.primitiveTopology(),key.format()));
    }
    @Override public void close() {
        motion.clear();storage.values().forEach(ByteBufferBuilder::close);storage.clear();builders.clear();particleStorage.close();
    }
}
