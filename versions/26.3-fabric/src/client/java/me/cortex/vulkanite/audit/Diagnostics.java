package me.cortex.vulkanite.audit;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Read-only lifecycle counters with opt-in performance sampling. Never opens sockets or mutates the
 * world. Profiling controls belong to the separate validation probe.
 */
public final class Diagnostics {
    public record Snapshot(
            long emptyMeshUpdates,
            long nonEmptyMeshUpdates,
            long emptyShortcutAccepts,
            long emptyShortcutSkipped,
            long removeSectionCalls,
            long removeSectionRemoved,
            long removeSectionNoHolder,
            long tangentFallbacks,
            long activeGeneration,
            long currentSections,
            long tlasInstances,
            long lastEmptyRevision,
            long lastNonEmptyRevision,
            long blasEnqueued,
            long blasPublished,
            long blasRejected,
            long cpuIndexedFenceNanos,
            long cpuIndexedReadbackNanos,
            long cpuAssembleNanos,
            long cpuPipelineNanos,
            long cpuTlasNanos,
            long gpuRayTicks,
            long gpuTlasTicks,
            long gpuDynBlasTicks,
            long gpuTerrainBlasTicks,
            long indexedDraws,
            long indexedBytes,
            long assembledVertices,
            long frameCount,
            long lastFrameNanos,
            long blasReused,
            String profileMode,
            long cpuTextureBridgeNanos,
            long cpuTerrainCopyNanos,
            long cpuTerrainUploadNanos,
            long cpuUniformWaitNanos,
            long gpuRayNanos,
            long gpuTlasNanos,
            long gpuDynBlasNanos,
            long gpuTerrainBlasNanos,
            long gpuRaySamples,
            long gpuTlasSamples,
            long gpuDynBlasSamples,
            long gpuTerrainBlasSamples,
            long gpuQueriesNotReady,
            long gpuQueriesFailed,
            long gpuSamplesSkipped,
            long cpuInteropSignalNanos,
            long cpuInteropWaitNanos,
            long cpuRetireNanos,
            long gpuGlSceneNanos,
            long gpuGlHandoffNanos,
            long gpuGlSceneSamples,
            long gpuGlHandoffSamples) {}

    public enum ProfileMode {
        OFF,
        CPU,
        GPU,
        ALL
    }

    private static volatile ProfileMode profileMode = startupMode();
    private static volatile long profileEpoch;
    private static final int GPU_INTERVAL =
            Math.clamp(Integer.getInteger("vulkanite.profile.gpuInterval", 32), 1, 4096);

    private static ProfileMode startupMode() {
        try {
            return ProfileMode.valueOf(
                    System.getProperty("vulkanite.profile", "off").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return ProfileMode.OFF;
        }
    }

    public static boolean cpuTimingEnabled() {
        return profileMode == ProfileMode.CPU || profileMode == ProfileMode.ALL;
    }

    public static long cpuStart() {
        return cpuTimingEnabled() ? System.nanoTime() : 0;
    }

    public static void cpuEnd(String span, long start) {
        if (start != 0 && cpuTimingEnabled()) addCpu(span, System.nanoTime() - start);
    }

    public static long gpuEpoch() {
        return profileMode == ProfileMode.GPU || profileMode == ProfileMode.ALL ? profileEpoch : -1;
    }

    public static int gpuInterval() {
        return GPU_INTERVAL;
    }

    /** Called on the render thread, so a mode transition cannot split a render-thread CPU span. */
    public static synchronized void setProfileMode(String mode) {
        ProfileMode selected = ProfileMode.valueOf(mode.toUpperCase(Locale.ROOT));
        profileMode = ProfileMode.OFF;
        resetPerf();
        profileMode = selected;
    }

    private static final AtomicLong emptyMeshUpdates = new AtomicLong();
    private static final AtomicLong nonEmptyMeshUpdates = new AtomicLong();
    private static final AtomicLong emptyShortcutAccepts = new AtomicLong();
    private static final AtomicLong emptyShortcutSkipped = new AtomicLong();
    private static final AtomicLong removeSectionCalls = new AtomicLong();
    private static final AtomicLong removeSectionRemoved = new AtomicLong();
    private static final AtomicLong removeSectionNoHolder = new AtomicLong();
    private static final AtomicLong tangentFallbacks = new AtomicLong();
    private static volatile long activeGeneration;
    private static volatile long currentSections;
    private static volatile long tlasInstances;
    private static volatile long lastEmptyRevision = -1;
    private static volatile long lastNonEmptyRevision = -1;
    private static final AtomicLong blasEnqueued = new AtomicLong();
    private static final AtomicLong blasPublished = new AtomicLong();
    private static final AtomicLong blasRejected = new AtomicLong();
    private static final AtomicLong blasReused = new AtomicLong();
    private static final AtomicLong cpuIndexedFenceNanos = new AtomicLong();
    private static final AtomicLong cpuIndexedReadbackNanos = new AtomicLong();
    private static final AtomicLong cpuAssembleNanos = new AtomicLong();
    private static final AtomicLong cpuPipelineNanos = new AtomicLong();
    private static final AtomicLong cpuTlasNanos = new AtomicLong();
    private static final AtomicLong cpuTextureBridgeNanos = new AtomicLong();
    private static final AtomicLong cpuTerrainCopyNanos = new AtomicLong();
    private static final AtomicLong cpuTerrainUploadNanos = new AtomicLong();
    private static final AtomicLong cpuUniformWaitNanos = new AtomicLong();
    private static final AtomicLong cpuInteropSignalNanos = new AtomicLong();
    private static final AtomicLong cpuInteropWaitNanos = new AtomicLong();
    private static final AtomicLong cpuRetireNanos = new AtomicLong();
    private static final AtomicLongArray gpuTicks = new AtomicLongArray(6);
    private static final AtomicLongArray gpuNanos = new AtomicLongArray(6);
    private static final AtomicLongArray gpuSamples = new AtomicLongArray(6);
    private static final AtomicLong gpuQueriesNotReady = new AtomicLong();
    private static final AtomicLong gpuQueriesFailed = new AtomicLong();
    private static final AtomicLong gpuSamplesSkipped = new AtomicLong();
    private static final AtomicLong indexedDraws = new AtomicLong();
    private static final AtomicLong indexedBytes = new AtomicLong();
    private static final AtomicLong assembledVertices = new AtomicLong();
    private static final AtomicLong frameCount = new AtomicLong();
    private static volatile long lastFrameNanos;
    private static final long[] frameRing = new long[512];
    private static int frameRingSize;
    private static int frameRingCursor;
    private static long previousFrameStart;

    /**
     * One cheap count per PT frame even when detailed sampling is disabled. Not a present count.
     */
    public static void beginFrame() {
        frameCount.incrementAndGet();
        if (profileMode == ProfileMode.OFF) return;
        long now = System.nanoTime();
        if (previousFrameStart != 0) recordFrame(now - previousFrameStart);
        previousFrameStart = now;
    }

    public static synchronized void recordFrame(long nanos) {
        if (profileMode == ProfileMode.OFF || nanos <= 0) return;
        lastFrameNanos = nanos;
        frameRing[frameRingCursor] = nanos;
        frameRingCursor = (frameRingCursor + 1) % frameRing.length;
        frameRingSize = Math.min(frameRingSize + 1, frameRing.length);
    }

    public static synchronized long[] frameTimesCopy() {
        int n = frameRingSize;
        long[] out = new long[n];
        if (frameRingSize < frameRing.length) {
            System.arraycopy(frameRing, 0, out, 0, n);
        } else {
            int start = frameRingCursor;
            for (int i = 0; i < n; i++) out[i] = frameRing[(start + i) % frameRing.length];
        }
        return out;
    }

    public static synchronized void resetPerf() {
        profileEpoch++;
        cpuIndexedFenceNanos.set(0);
        cpuIndexedReadbackNanos.set(0);
        cpuAssembleNanos.set(0);
        cpuPipelineNanos.set(0);
        cpuTlasNanos.set(0);
        cpuTextureBridgeNanos.set(0);
        cpuTerrainCopyNanos.set(0);
        cpuTerrainUploadNanos.set(0);
        cpuUniformWaitNanos.set(0);
        cpuInteropSignalNanos.set(0);
        cpuInteropWaitNanos.set(0);
        cpuRetireNanos.set(0);
        for (int slot = 0; slot < gpuTicks.length(); slot++) {
            gpuTicks.set(slot, 0);
            gpuNanos.set(slot, 0);
            gpuSamples.set(slot, 0);
        }
        gpuQueriesNotReady.set(0);
        gpuQueriesFailed.set(0);
        gpuSamplesSkipped.set(0);
        indexedDraws.set(0);
        indexedBytes.set(0);
        assembledVertices.set(0);
        frameRingSize = 0;
        frameRingCursor = 0;
        previousFrameStart = 0;
        lastFrameNanos = 0;
        frameCount.set(0);
    }

    private Diagnostics() {}

    public static Snapshot snapshot() {
        return new Snapshot(
                emptyMeshUpdates.get(),
                nonEmptyMeshUpdates.get(),
                emptyShortcutAccepts.get(),
                emptyShortcutSkipped.get(),
                removeSectionCalls.get(),
                removeSectionRemoved.get(),
                removeSectionNoHolder.get(),
                tangentFallbacks.get(),
                activeGeneration,
                currentSections,
                tlasInstances,
                lastEmptyRevision,
                lastNonEmptyRevision,
                blasEnqueued.get(),
                blasPublished.get(),
                blasRejected.get(),
                cpuIndexedFenceNanos.get(),
                cpuIndexedReadbackNanos.get(),
                cpuAssembleNanos.get(),
                cpuPipelineNanos.get(),
                cpuTlasNanos.get(),
                gpuTicks.get(0),
                gpuTicks.get(1),
                gpuTicks.get(2),
                gpuTicks.get(3),
                indexedDraws.get(),
                indexedBytes.get(),
                assembledVertices.get(),
                frameCount.get(),
                lastFrameNanos,
                blasReused.get(),
                profileMode.name().toLowerCase(Locale.ROOT),
                cpuTextureBridgeNanos.get(),
                cpuTerrainCopyNanos.get(),
                cpuTerrainUploadNanos.get(),
                cpuUniformWaitNanos.get(),
                gpuNanos.get(0),
                gpuNanos.get(1),
                gpuNanos.get(2),
                gpuNanos.get(3),
                gpuSamples.get(0),
                gpuSamples.get(1),
                gpuSamples.get(2),
                gpuSamples.get(3),
                gpuQueriesNotReady.get(),
                gpuQueriesFailed.get(),
                gpuSamplesSkipped.get(),
                cpuInteropSignalNanos.get(),
                cpuInteropWaitNanos.get(),
                cpuRetireNanos.get(),
                gpuNanos.get(4),
                gpuNanos.get(5),
                gpuSamples.get(4),
                gpuSamples.get(5));
    }

    public static void onEmptyMeshUpdate(long revision) {
        emptyMeshUpdates.incrementAndGet();
        lastEmptyRevision = revision;
    }

    public static void onNonEmptyMeshUpdate(long revision) {
        nonEmptyMeshUpdates.incrementAndGet();
        lastNonEmptyRevision = revision;
    }

    public static void onEmptyShortcutAccepted() {
        emptyShortcutAccepts.incrementAndGet();
    }

    public static void onEmptyShortcutSkipped() {
        emptyShortcutSkipped.incrementAndGet();
    }

    public static void onRemoveSection(boolean removed) {
        removeSectionCalls.incrementAndGet();
        if (removed) removeSectionRemoved.incrementAndGet();
        else removeSectionNoHolder.incrementAndGet();
    }

    public static void onTangentFallback() {
        tangentFallbacks.incrementAndGet();
    }

    public static void onBlasEnqueued(int count) {
        blasEnqueued.addAndGet(Math.max(0, count));
    }

    public static void onBlasPublished(int count) {
        blasPublished.addAndGet(Math.max(0, count));
    }

    public static void onBlasRejected(int count) {
        blasRejected.addAndGet(Math.max(0, count));
    }

    public static void onBlasReused() {
        blasReused.incrementAndGet();
    }

    public static void setGeneration(long generation) {
        activeGeneration = generation;
    }

    public static void setSectionCounts(long sections, long tlasInstanceCount) {
        currentSections = sections;
        tlasInstances = tlasInstanceCount;
    }

    public static void addCpu(String span, long nanos) {
        if (!cpuTimingEnabled()) return;
        switch (span) {
            case "indexedFence" -> cpuIndexedFenceNanos.addAndGet(nanos);
            case "indexedReadback" -> cpuIndexedReadbackNanos.addAndGet(nanos);
            case "assemble" -> cpuAssembleNanos.addAndGet(nanos);
            case "pipeline" -> cpuPipelineNanos.addAndGet(nanos);
            case "tlasCpu" -> cpuTlasNanos.addAndGet(nanos);
            case "textureBridge" -> cpuTextureBridgeNanos.addAndGet(nanos);
            case "terrainCopy" -> cpuTerrainCopyNanos.addAndGet(nanos);
            case "terrainUpload" -> cpuTerrainUploadNanos.addAndGet(nanos);
            case "uniformWait" -> cpuUniformWaitNanos.addAndGet(nanos);
            case "interopSignal" -> cpuInteropSignalNanos.addAndGet(nanos);
            case "interopWait" -> cpuInteropWaitNanos.addAndGet(nanos);
            case "retire" -> cpuRetireNanos.addAndGet(nanos);
            default -> {}
        }
    }

    public static void addIndexed(int draws, long bytes) {
        if (profileMode == ProfileMode.OFF) return;
        indexedDraws.addAndGet(draws);
        indexedBytes.addAndGet(bytes);
    }

    public static void addAssembled(long vertices) {
        if (profileMode == ProfileMode.OFF) return;
        assembledVertices.addAndGet(vertices);
    }

    public static synchronized void onGpuTimestamp(long epoch, int slot, long ticks, long nanos) {
        if (epoch != gpuEpoch() || epoch < 0 || slot < 0 || slot >= gpuTicks.length()) return;
        gpuTicks.set(slot, ticks);
        gpuNanos.addAndGet(slot, nanos);
        gpuSamples.incrementAndGet(slot);
    }

    public static synchronized void onGpuQuerySkipped(long epoch, boolean notReady) {
        if (epoch != gpuEpoch() || epoch < 0) return;
        if (notReady) gpuQueriesNotReady.incrementAndGet();
        else gpuQueriesFailed.incrementAndGet();
    }

    public static synchronized void onGpuSampleSkipped(long epoch) {
        if (epoch == gpuEpoch() && epoch >= 0) gpuSamplesSkipped.incrementAndGet();
    }
}
