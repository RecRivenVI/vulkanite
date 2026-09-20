package me.cortex.vulkanite.client.rendering.interop;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.vulkanite.compat.EntityFrame;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** Resolves Minecraft and Iris texture inputs into Vulkanite's read-only sampled images. */
public final class TextureInputs {
    private TextureInputs() {}

    /** Return a sampled view when this input has a Vulkan backing; otherwise return {@code null}. */
    public static VGImage image(Object input) {
        if (input instanceof IVGImage customImage) {
            VGImage image = customImage.getVGImage();
            if (image != null) return image;
        }

        GpuTexture texture = gpuTexture(input);
        if (texture instanceof GlTexture glTexture) return SampledTextureBridge.get(glTexture);
        if (texture instanceof IVGImage sharedImage) return sharedImage.getVGImage();
        return null;
    }

    /** Resolve a required albedo input, keeping unsupported textures explicit at the caller. */
    public static VGImage requireImage(Object input) {
        VGImage image = image(input);
        if (image == null) throw new IllegalStateException("Texture has no Vulkan sampled image: " + input);
        return image;
    }

    /** Retain a supported full 2D view and the producer's sampler semantics, not its owner. */
    public static EntityFrame.MaterialTextures materialTextures(GpuTextureView view,GpuSampler sampler) {
        if (view==null || view.isClosed() || sampler==null || sampler.isClosed())
            throw new IllegalArgumentException("Material texture view or sampler is unavailable");
        GpuTexture texture=view.texture();
        if (texture==null || texture.isClosed() || texture.getDepthOrLayers()!=1
                || view.baseMipLevel()!=0 || view.mipLevels()!=texture.getMipLevels())
            throw new IllegalArgumentException("Only complete 2D material texture views are supported");
        VGImage albedo = requireImage(texture);
        PBRTextureHolder holder = pbrHolder(texture);
        var state=samplerState(sampler,view.mipLevels());
        if (holder == null) return new EntityFrame.MaterialTextures(albedo, null, null, state);
        return new EntityFrame.MaterialTextures(albedo, image(holder.normalTexture()),
                image(holder.specularTexture()), state);
    }

    public static EntityFrame.SamplerState samplerState(GpuSampler sampler,int mipLevels) {
        if (mipLevels<1) throw new IllegalArgumentException("Material view has no mip levels");
        double requested=sampler.getMaxLod().orElse(mipLevels-1);
        if (!Double.isFinite(requested) || requested<0)
            throw new IllegalArgumentException("Material sampler has an invalid max LOD");
        return new EntityFrame.SamplerState(address(sampler.getAddressModeU()),
                address(sampler.getAddressModeV()),filter(sampler.getMinFilter()),
                filter(sampler.getMagFilter()),sampler.getMaxAnisotropy(),
                (float)Math.min(requested,mipLevels-1));
    }

    private static EntityFrame.Address address(AddressMode mode) {
        return switch(mode) {
            case REPEAT -> EntityFrame.Address.REPEAT;
            case CLAMP_TO_EDGE -> EntityFrame.Address.CLAMP_TO_EDGE;
        };
    }

    private static EntityFrame.Filter filter(FilterMode mode) {
        return switch(mode) {
            case NEAREST -> EntityFrame.Filter.NEAREST;
            case LINEAR -> EntityFrame.Filter.LINEAR;
        };
    }

    public static VGImage blockAtlasAlbedo() {
        return image(blockAtlasTexture());
    }

    public static EntityFrame.SamplerState blockAtlasSamplerState() {
        var atlas=blockAtlasTexture();
        var view=atlas.getTextureView();
        var texture=atlas.getTexture();
        if (view.isClosed() || texture.getDepthOrLayers()!=1
                || view.baseMipLevel()!=0 || view.mipLevels()!=texture.getMipLevels())
            throw new IllegalStateException("Block atlas sampler has a partial or closed texture view");
        return samplerState(atlas.getSampler(),view.mipLevels());
    }

    public static VGImage blockAtlasNormal() {
        return pbrImage(blockAtlasTexture(), true);
    }

    public static VGImage blockAtlasSpecular() {
        return pbrImage(blockAtlasTexture(), false);
    }

    public static void notifyPbrTexturesChanged() {
        PBRTextureManager.notifyPBRTexturesChanged();
    }

    private static AbstractTexture blockAtlasTexture() {
        return Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
    }

    private static VGImage pbrImage(AbstractTexture texture, boolean normal) {
        PBRTextureHolder holder = pbrHolder(gpuTexture(texture));
        if (holder == null) return null;
        return image(normal ? holder.normalTexture() : holder.specularTexture());
    }

    private static PBRTextureHolder pbrHolder(GpuTexture texture) {
        if (!(texture instanceof GpuTextureInterface irisTexture)) return null;
        return PBRTextureManager.INSTANCE.getOrLoadHolder(irisTexture.iris$getGlId());
    }

    private static GpuTexture gpuTexture(Object input) {
        if (input instanceof AbstractTexture texture) return texture.getTexture();
        return input instanceof GpuTexture texture ? texture : null;
    }
}
