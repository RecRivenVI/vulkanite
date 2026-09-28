package me.cortex.vulkanite.audit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Dev-oriented read-only lifecycle counters. Always on and cheap; never opens
 * sockets or mutates the world. An external audit harness may poll
 * {@link #snapshot()} after process start.
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
            long lastFrameNanos
    ) {}

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
    private static volatile long gpuRayTicks;
    private static volatile long gpuTlasTicks;
    private static volatile long gpuDynBlasTicks;
    private static volatile long gpuTerrainBlasTicks;
    private static final AtomicLong indexedDraws = new AtomicLong();
    private static final AtomicLong indexedBytes = new AtomicLong();
    private static final AtomicLong assembledVertices = new AtomicLong();
    private static final AtomicLong frameCount = new AtomicLong();
    private static volatile long lastFrameNanos;
    private static final long[] frameRing = new long[512];
    private static int frameRingLen;

    public static synchronized void recordFrame(long nanos) {
        frameCount.incrementAndGet();
        lastFrameNanos = nanos;
        frameRing[frameRingLen % frameRing.length] = nanos;
        frameRingLen++;
    }

    public static synchronized long[] frameTimesCopy() {
        int n = Math.min(frameRingLen, frameRing.length);
        long[] out = new long[n];
        if (frameRingLen <= frameRing.length) {
            System.arraycopy(frameRing, 0, out, 0, n);
        } else {
            int start = frameRingLen % frameRing.length;
            for (int i = 0; i < n; i++) out[i] = frameRing[(start + i) % frameRing.length];
        }
        return out;
    }

    public static synchronized void resetPerf() {
        cpuIndexedFenceNanos.set(0);
        cpuIndexedReadbackNanos.set(0);
        cpuAssembleNanos.set(0);
        cpuPipelineNanos.set(0);
        cpuTlasNanos.set(0);
        indexedDraws.set(0);
        indexedBytes.set(0);
        assembledVertices.set(0);
        frameRingLen = 0;
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
                gpuRayTicks,
                gpuTlasTicks,
                gpuDynBlasTicks,
                gpuTerrainBlasTicks,
                indexedDraws.get(),
                indexedBytes.get(),
                assembledVertices.get(),
                frameCount.get(),
                lastFrameNanos);
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
        switch (span) {
            case "indexedFence" -> cpuIndexedFenceNanos.addAndGet(nanos);
            case "indexedReadback" -> cpuIndexedReadbackNanos.addAndGet(nanos);
            case "assemble" -> cpuAssembleNanos.addAndGet(nanos);
            case "pipeline" -> cpuPipelineNanos.addAndGet(nanos);
            case "tlasCpu" -> cpuTlasNanos.addAndGet(nanos);
            default -> {}
        }
    }

    public static void addIndexed(int draws, long bytes) {
        indexedDraws.addAndGet(draws);
        indexedBytes.addAndGet(bytes);
    }

    public static void addAssembled(long vertices) {
        assembledVertices.addAndGet(vertices);
    }

    /** span[0]=ray, [1]=tlas, [2]=dynBlas, [3]=terrainBlas in timestamp ticks. */
    public static void onGpuTimestamps(long[] span) {
        if (span == null || span.length < 4) return;
        gpuRayTicks = span[0];
        gpuTlasTicks = span[1];
        gpuDynBlasTicks = span[2];
        gpuTerrainBlasTicks = span[3];
    }
}
