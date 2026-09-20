package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;

import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AccelerationManager {
    private final VContext ctx;

    private final AccelerationBlasBuilder blasBuilder;
    private final ConcurrentLinkedDeque<AccelerationBlasBuilder.BLASBatchResult> blasResults = new ConcurrentLinkedDeque<>();

    private final AccelerationTLASManager tlasManager;
    private record BuildRevision(long revision, long generation) {}
    private final java.util.Map<SectionHandle, BuildRevision> latestBuilds = new java.util.IdentityHashMap<>();
    private final java.util.Map<SectionHandle.Origin, SectionHandle> currentSections = new java.util.HashMap<>();

    public AccelerationManager(VContext context, int blasBuildQueue) {
        this.ctx = context;
        this.blasBuilder = new AccelerationBlasBuilder(context, blasBuildQueue, blasResults::add);
        this.tlasManager = new AccelerationTLASManager(context, 0);//TODO: pick the main queue or something? (maybe can do the blasBuildQueue)
    }

    public void chunkBuilds(List<SectionMeshUpdate> results, long activeGeneration) {
        List<SectionMeshUpdate> accepted = new ArrayList<>(results.size());
        for (var result : results) {
            var section = result.section();
            if (section.isDisposed() || result.generation() != activeGeneration) continue;
            var displaced = currentSections.put(section.origin(), section);
            if (displaced != null && displaced != section) {
                latestBuilds.remove(displaced);
                tlasManager.removeSection(displaced);
            }
            latestBuilds.put(section, new BuildRevision(result.revision(), result.generation()));
            if (result.isEmpty()) {
                tlasManager.removeSection(section);
            } else accepted.add(result);
        }
        try { blasBuilder.enqueue(accepted); }
        catch (Throwable failure) {
            // The replacement was not published. Do not leave a previous BLAS
            // visible under an update whose upload failed halfway through.
            for (var update : accepted) {
                var section = update.section();
                latestBuilds.remove(section);
                currentSections.remove(section.origin(), section);
                try { tlasManager.removeSection(section); }
                catch (Throwable releaseFailure) { failure.addSuppressed(releaseFailure); }
            }
            throw failure;
        }
    }


    public void setEntityData(me.cortex.vulkanite.compat.EntityFrame data) {
        tlasManager.setEntityData(data);
    }

    private final List<VSemaphore> syncs = new LinkedList<>();

    //This updates the tlas internal structure, DOES NOT INCLUDING BUILDING THE TLAS
    public void updateTick(long activeGeneration, boolean sceneGeometryActive) {
        blasBuilder.drainRetiredCommands();
        if (!blasResults.isEmpty()) {//If there are results
            //Atomicly collect the results from the queue
            List<AccelerationBlasBuilder.BLASBuildResult> results = new LinkedList<>();
            while (!blasResults.isEmpty()) {
                var batch = blasResults.poll();
                for (var result : batch.results()) {
                    var data = result.data();
                    if (sceneGeometryActive && data.generation() == activeGeneration
                            && java.util.Objects.equals(latestBuilds.get(data.section()),
                                    new BuildRevision(data.revision(), data.generation()))
                            && currentSections.get(data.section().origin()) == data.section()
                            && !data.section().isDisposed()) {
                        results.add(result);
                    } else {
                        tlasManager.reject(result);
                    }
                }
                syncs.add(batch.semaphore());
            }
            tlasManager.updateSections(results);
        }
    }

    public VAccelerationStructure buildTLAS(VSemaphore inLink, VSemaphore outLink) {
        blasBuilder.assertHealthy();
        tlasManager.buildTLAS(inLink, outLink, syncs.toArray(new VSemaphore[0]));
        syncs.clear();
        return tlasManager.getTlas();
    }

    public void sectionRemove(SectionHandle section) {
        section.dispose();
        latestBuilds.remove(section);
        currentSections.remove(section.origin(), section);
        tlasManager.removeSection(section);
    }

    //Cleans up any loose things such as semaphores waiting to be synced etc
    public void cleanup() {
        Throwable failure = null;
        for (var section : currentSections.values()) try { tlasManager.removeSection(section); }
        catch (Throwable removeFailure) { failure = combine(failure, removeFailure); }
        latestBuilds.clear();
        currentSections.clear();
        try { blasBuilder.awaitIdle(); }
        catch (Throwable drainFailure) { failure = combine(failure, drainFailure); }
        if (!blasBuilder.isDrained()) throw propagate(combine(failure,
                new IllegalStateException("BLAS worker is not drained; retaining GPU resources")));

        boolean queuesIdle = true;
        try { ctx.cmd.waitQueueIdle(0); }
        catch (Throwable unsafe) { queuesIdle = false; failure = combine(failure, unsafe); }
        try { ctx.cmd.waitQueueIdle(1); }
        catch (Throwable unsafe) { queuesIdle = false; failure = combine(failure, unsafe); }
        if (!queuesIdle) throw propagate(combine(failure,
                new IllegalStateException("GPU queue idle was not confirmed; retaining resources")));

        boolean callbacksComplete = true;
        try { ctx.sync.checkFences(); }
        catch (Throwable callbackFailure) {
            callbacksComplete = false;
            failure = combine(failure, callbackFailure);
        }
        try { blasBuilder.drainRetiredCommands(); }
        catch (Throwable releaseFailure) { failure = combine(failure, releaseFailure); }
        if (ctx.sync.pendingCallbackFences() != 0) throw propagate(combine(failure,
                new IllegalStateException("Fence callbacks remain pending after queue idle; retaining GPU resources")));
        try { blasBuilder.drainQuarantinedAfterIdle(); }
        catch (Throwable releaseFailure) { failure = combine(failure, releaseFailure); }
        if (!callbacksComplete) throw propagate(failure);
        while (!blasResults.isEmpty()) {
            var batch = blasResults.poll();
            for (var result : batch.results()) {
                if (!result.structure().isFreed()) try { result.structure().free(); }
                catch (Throwable releaseFailure) { failure = combine(failure, releaseFailure); }
                for (var buffer : result.data().geometryBuffers()) if (!buffer.isFreed()) try { buffer.free(); }
                catch (Throwable releaseFailure) { failure = combine(failure, releaseFailure); }
            }
            if (!batch.semaphore().isFreed()) try { batch.semaphore().free(); }
            catch (Throwable releaseFailure) { failure = combine(failure, releaseFailure); }
        }
        for (var semaphore : syncs) if (!semaphore.isFreed()) try { semaphore.free(); }
        catch (Throwable releaseFailure) { failure = combine(failure, releaseFailure); }
        syncs.clear();
        try { tlasManager.cleanupTick(); }
        catch (Throwable releaseFailure) { failure = combine(failure, releaseFailure); }
        if (failure != null) throw propagate(failure);
    }

    private static Throwable combine(Throwable primary, Throwable additional) {
        if (primary == null) return additional;
        if (primary != additional) primary.addSuppressed(additional);
        return primary;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Acceleration cleanup failed", failure);
    }

    public long getGeometrySet() {
        return tlasManager.getGeometrySet();
    }

    public VDescriptorSetLayout getGeometryLayout() {
        return tlasManager.getGeometryLayout();
    }
}
