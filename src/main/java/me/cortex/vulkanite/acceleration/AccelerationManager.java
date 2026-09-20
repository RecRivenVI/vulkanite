package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.apache.commons.lang3.tuple.Pair;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AccelerationManager {
    private final VContext ctx;

    private final AccelerationBlasBuilder blasBuilder;
    private final ConcurrentLinkedDeque<AccelerationBlasBuilder.BLASBatchResult> blasResults = new ConcurrentLinkedDeque<>();

    private final AccelerationTLASManager tlasManager;
    private final java.util.Map<RenderSection, Long> latestBuilds = new java.util.IdentityHashMap<>();

    public AccelerationManager(VContext context, int blasBuildQueue) {
        this.ctx = context;
        this.blasBuilder = new AccelerationBlasBuilder(context, blasBuildQueue, blasResults::add);
        this.tlasManager = new AccelerationTLASManager(context, 0);//TODO: pick the main queue or something? (maybe can do the blasBuildQueue)
    }

    public void chunkBuilds(List<ChunkBuildOutput> results) {
        for (var result : results) {
            latestBuilds.put(result.section, (long) result.submitTime);
            var geometry = ((me.cortex.vulkanite.compat.IAccelerationBuildResult) result).getAccelerationGeometryData();
            if (geometry != null && geometry.isEmpty()) tlasManager.removeSection(result.section);
        }
        blasBuilder.enqueue(results);
    }


    public void setEntityData(me.cortex.vulkanite.compat.EntityFrame data) {
        tlasManager.setEntityData(data);
    }

    private final List<VSemaphore> syncs = new LinkedList<>();

    //This updates the tlas internal structure, DOES NOT INCLUDING BUILDING THE TLAS
    public void updateTick() {
        if (!blasResults.isEmpty()) {//If there are results
            //Atomicly collect the results from the queue
            List<AccelerationBlasBuilder.BLASBuildResult> results = new LinkedList<>();
            while (!blasResults.isEmpty()) {
                var batch = blasResults.poll();
                for (var result : batch.results()) {
                    if (java.util.Objects.equals(latestBuilds.get(result.data().section()), result.data().time())) results.add(result);
                    else tlasManager.reject(result);
                }
                syncs.add(batch.semaphore());
            }
            tlasManager.updateSections(results);
        }
    }

    public VAccelerationStructure buildTLAS(VSemaphore inLink, VSemaphore outLink) {
        tlasManager.buildTLAS(inLink, outLink, syncs.toArray(new VSemaphore[0]));
        syncs.clear();
        return tlasManager.getTlas();
    }

    public void sectionRemove(RenderSection section) {
        latestBuilds.remove(section);
        tlasManager.removeSection(section);
    }

    //Cleans up any loose things such as semaphores waiting to be synced etc
    public void cleanup() {
        latestBuilds.clear();
        blasBuilder.awaitIdle();
        ctx.cmd.waitQueueIdle(0);
        ctx.cmd.waitQueueIdle(1);
        ctx.sync.checkFences();
        while (!blasResults.isEmpty()) {
            var batch = blasResults.poll();
            for (var result : batch.results()) {
                result.structure().free();
                result.data().geometryBuffers().forEach(VBuffer::free);
            }
            batch.semaphore().free();
        }
        syncs.forEach(VSemaphore::free);
        syncs.clear();
        tlasManager.cleanupTick();
    }

    public long getGeometrySet() {
        return tlasManager.getGeometrySet();
    }

    public VDescriptorSetLayout getGeometryLayout() {
        return tlasManager.getGeometryLayout();
    }
}
