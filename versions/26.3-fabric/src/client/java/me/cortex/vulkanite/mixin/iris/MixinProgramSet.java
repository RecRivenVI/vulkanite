package me.cortex.vulkanite.mixin.iris;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import me.cortex.vulkanite.compat.IGetRaytracingSource;
import me.cortex.vulkanite.compat.PackCapabilities;
import me.cortex.vulkanite.compat.RaytracingShaderSource;
import me.cortex.vulkanite.compat.VulkanPassContract;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ProgramSet.class, remap = false)
public abstract class MixinProgramSet implements IGetRaytracingSource {
    @Unique private RaytracingShaderSource[] sources;
    @Unique private PackCapabilities vulkanite$capabilities = PackCapabilities.OPENGL;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShaders(
            AbsolutePackPath directory,
            Function<AbsolutePackPath, String> sourceProvider,
            ShaderProperties shaderProperties,
            ShaderPack pack,
            CallbackInfo ci) {
        List<RaytracingShaderSource> sourceList = new ArrayList<>();
        int passId = 0;
        while (true) {
            int pass = passId++;
            var gen = sourceProvider.apply(directory.resolve("ray" + pass + ".rgen"));
            if (gen == null) break;
            List<String> missSources = new ArrayList<>();
            int missId = 0;
            while (true) {
                var miss =
                        sourceProvider.apply(
                                directory.resolve("ray" + pass + "_" + (missId++) + ".rmiss"));
                if (miss == null) break;
                missSources.add(miss);
            }
            List<RaytracingShaderSource.RayHitSource> hitSources = new ArrayList<>();
            int hitId = 0;
            while (true) {
                int hit = hitId++;
                var close =
                        sourceProvider.apply(
                                directory.resolve("ray" + pass + "_" + hit + ".rchit"));
                var any =
                        sourceProvider.apply(
                                directory.resolve("ray" + pass + "_" + hit + ".rahit"));
                var intersect =
                        sourceProvider.apply(directory.resolve("ray" + pass + "_" + hit + ".rint"));
                if (close == null && any == null && intersect == null) break;
                hitSources.add(new RaytracingShaderSource.RayHitSource(close, any, intersect));
            }
            if (missSources.isEmpty()) {
                throw new IllegalStateException("No miss shaders for pass " + pass);
            }
            if (hitSources.isEmpty()) {
                throw new IllegalStateException("No hit shaders for pass " + pass);
            }
            sourceList.add(
                    new RaytracingShaderSource(
                            "raypass_" + pass,
                            gen,
                            missSources.toArray(new String[0]),
                            hitSources.toArray(new RaytracingShaderSource.RayHitSource[0])));
        }
        if (!sourceList.isEmpty()) {
            sources = sourceList.toArray(new RaytracingShaderSource[0]);
            // One pack-level contract is shared across dimension ProgramSets.
            String requirements =
                    sourceProvider.apply(
                            AbsolutePackPath.fromAbsolutePath("/vulkanite.properties"));
            vulkanite$capabilities = vulkanite$readCapabilities(requirements, pack);
        }
    }

    @Override
    public RaytracingShaderSource[] getRaytracingSource() {
        return sources;
    }

    @Override
    public PackCapabilities vulkanite$capabilities() {
        return vulkanite$capabilities;
    }

    @Unique
    private PackCapabilities vulkanite$readCapabilities(String source, ShaderPack pack) {
        if (source == null) {
            throw vulkanite$invalid(
                    "file", "required sidecar is missing; ray shaders cannot be initialized");
        }

        Properties properties = new Properties();
        try {
            properties.load(new StringReader(source));
        } catch (IOException | IllegalArgumentException malformed) {
            throw vulkanite$invalid("properties syntax", "cannot parse the sidecar", malformed);
        }

        String schema = properties.getProperty("schema");
        if (schema == null || !"2".equals(schema.trim())) {
            throw vulkanite$invalid("schema", "expected schema 2, found " + schema);
        }

        final int sharedColorTargets;
        final boolean sceneGeometry;
        String targetCount = properties.getProperty("sharedColorTargets");
        if (targetCount == null) {
            throw vulkanite$invalid(
                    "sharedColorTargets", "required contiguous target prefix is missing");
        }
        try {
            sharedColorTargets = Integer.parseInt(targetCount.trim());
        } catch (NumberFormatException malformed) {
            throw vulkanite$invalid(
                    "sharedColorTargets",
                    "expected a positive integer, found " + targetCount,
                    malformed);
        }

        String geometry = properties.getProperty("sceneGeometry");
        if (geometry == null
                || (!geometry.trim().equalsIgnoreCase("true")
                        && !geometry.trim().equalsIgnoreCase("false"))) {
            throw vulkanite$invalid("sceneGeometry", "expected true or false, found " + geometry);
        }
        sceneGeometry = Boolean.parseBoolean(geometry.trim());

        ProgramSet programSet = (ProgramSet) (Object) this;
        var supportedTargets =
                programSet
                        .getPackDirectives()
                        .getRenderTargetDirectives()
                        .getRenderTargetSettings();
        if (sharedColorTargets <= 0 || sharedColorTargets > supportedTargets.size()) {
            throw vulkanite$invalid(
                    "sharedColorTargets",
                    "expected a prefix in 1.."
                            + supportedTargets.size()
                            + ", found "
                            + sharedColorTargets);
        }
        for (int target = 0; target < sharedColorTargets; target++) {
            if (!supportedTargets.containsKey(target)) {
                throw vulkanite$invalid(
                        "sharedColorTargets", "prefix includes unsupported colortex" + target);
            }
        }

        VulkanPassContract execution = vulkanite$readExecution(properties, pack);
        if (execution.colorWrite() >= sharedColorTargets
                || execution.colorReads().stream().anyMatch(id -> id >= sharedColorTargets))
            throw vulkanite$invalid(
                    "color", "every read/write target must be in the shared prefix");
        for (int target : execution.colorReads())
            if (!supportedTargets.containsKey(target))
                throw vulkanite$invalid("color.read", "colortex" + target + " is unavailable");
        if (!supportedTargets.containsKey(execution.colorWrite()))
            throw vulkanite$invalid(
                    "color.write", "colortex" + execution.colorWrite() + " is unavailable");

        var gbufferTextures = pack.getCustomTextureDataMap().get(TextureStage.GBUFFERS_AND_SHADOW);
        boolean sharedCustomTextures =
                !pack.getIrisCustomTextureDataMap().isEmpty()
                        || gbufferTextures != null && !gbufferTextures.isEmpty();
        return new PackCapabilities(
                sharedColorTargets, sharedCustomTextures, sceneGeometry, Optional.of(execution));
    }

    @Unique
    private VulkanPassContract vulkanite$readExecution(Properties properties, ShaderPack pack) {
        Set<String> fixedKeys =
                Set.of(
                        "schema",
                        "sharedColorTargets",
                        "sceneGeometry",
                        "execution.phase",
                        "execution.after",
                        "color.read",
                        "color.write");
        Map<Integer, Long> storage = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (fixedKeys.contains(key)) continue;
            if (!key.startsWith("storage.") || !key.endsWith(".minimumBytes"))
                throw vulkanite$invalid(key, "unknown schema 2 property");
            String number =
                    key.substring("storage.".length(), key.length() - ".minimumBytes".length());
            int binding = vulkanite$integer(key, number);
            long minimumBytes = vulkanite$long(key, properties.getProperty(key));
            if (binding < 0 || minimumBytes <= 0)
                throw vulkanite$invalid(
                        key, "binding must be non-negative and size must be positive");
            if (storage.putIfAbsent(binding, minimumBytes) != null)
                throw vulkanite$invalid(key, "duplicate storage binding " + binding);
            var buffer = pack.getBufferObjects().get(binding);
            if (buffer == null || buffer.size() < minimumBytes)
                throw vulkanite$invalid(
                        key,
                        "Iris bufferObject."
                                + binding
                                + " is missing or smaller than "
                                + minimumBytes
                                + " bytes");
        }
        String phaseName = properties.getProperty("execution.phase");
        if (!"COMPOSITE".equals(phaseName))
            throw vulkanite$invalid(
                    "execution.phase", "the current single-segment executor requires COMPOSITE");
        String after = properties.getProperty("execution.after");
        if (after == null || after.isBlank())
            throw vulkanite$invalid("execution.after", "name the completed Iris composite pass");
        vulkanite$requireAnchor(after.trim());
        String reads = properties.getProperty("color.read");
        if (reads == null)
            throw vulkanite$invalid(
                    "color.read", "declare a comma-separated list or an empty value");
        List<Integer> readTargets =
                reads.isBlank()
                        ? List.of()
                        : java.util.Arrays.stream(reads.split(",", -1))
                                .map(value -> vulkanite$integer("color.read", value.trim()))
                                .toList();
        int writeTarget = vulkanite$integer("color.write", properties.getProperty("color.write"));
        try {
            return new VulkanPassContract(
                    VulkanPassContract.Phase.COMPOSITE,
                    after.trim(),
                    readTargets,
                    writeTarget,
                    storage);
        } catch (IllegalArgumentException malformed) {
            throw vulkanite$invalid("execution", malformed.getMessage(), malformed);
        }
    }

    @Unique
    private void vulkanite$requireAnchor(String name) {
        ProgramSet set = (ProgramSet) (Object) this;
        ProgramSource[] rasters = set.getComposite(ProgramArrayId.Composite);
        ComputeSource[][] computes = set.getCompute(ProgramArrayId.Composite);
        int matches = 0;
        for (int slot = 0; slot < rasters.length; slot++) {
            ProgramSource raster = rasters[slot];
            String actual = raster != null && raster.isValid() ? raster.getName() : null;
            if (actual == null
                    && computes != null
                    && slot < computes.length
                    && computes[slot] != null)
                for (ComputeSource compute : computes[slot])
                    if (compute != null && compute.isValid()) {
                        actual = compute.getName();
                        break;
                    }
            if (name.equals(actual)) matches++;
        }
        if (matches != 1)
            throw vulkanite$invalid(
                    "execution.after",
                    "expected one active composite pass named " + name + ", found " + matches);
    }

    @Unique
    private static int vulkanite$integer(String key, String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException malformed) {
            throw vulkanite$invalid(key, "expected integer, found " + value, malformed);
        }
    }

    @Unique
    private static long vulkanite$long(String key, String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException malformed) {
            throw vulkanite$invalid(key, "expected long integer, found " + value, malformed);
        }
    }

    @Unique
    private static IllegalArgumentException vulkanite$invalid(String key, String detail) {
        return vulkanite$invalid(key, detail, null);
    }

    @Unique
    private static IllegalArgumentException vulkanite$invalid(
            String key, String detail, Throwable cause) {
        return new IllegalArgumentException(
                "Invalid shaders/vulkanite.properties [" + key + "]: " + detail, cause);
    }
}
