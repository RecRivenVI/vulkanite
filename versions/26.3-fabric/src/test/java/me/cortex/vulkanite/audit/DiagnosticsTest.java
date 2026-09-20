package me.cortex.vulkanite.audit;

import static org.junit.jupiter.api.Assertions.*;

import me.cortex.vulkanite.lib.other.GpuTimestamps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {
    @AfterEach
    void restoreDefault() {
        Diagnostics.setProfileMode("off");
    }

    @Test
    void disabledSamplingKeepsFrameCountButDoesNotRecordTimings() {
        Diagnostics.setProfileMode("off");
        for (int i = 0; i < 800; i++) Diagnostics.beginFrame();
        Diagnostics.addCpu("pipeline", 123);
        Diagnostics.addIndexed(4, 64);
        assertEquals(800, Diagnostics.snapshot().frameCount());
        assertEquals(0, Diagnostics.snapshot().cpuPipelineNanos());
        assertEquals(0, Diagnostics.snapshot().indexedBytes());
        assertEquals(0, Diagnostics.frameTimesCopy().length);
        assertEquals(0, Diagnostics.cpuStart());
        assertEquals(-1, Diagnostics.gpuEpoch());
    }

    @Test
    void ringRemainsBoundedAndChronological() {
        Diagnostics.setProfileMode("cpu");
        for (int i = 1; i <= 800; i++) Diagnostics.recordFrame(i);
        long[] frames = Diagnostics.frameTimesCopy();
        assertEquals(512, frames.length);
        assertEquals(289, frames[0]);
        assertEquals(800, frames[511]);
        Diagnostics.resetPerf();
        assertEquals(0, Diagnostics.frameTimesCopy().length);
    }

    @Test
    void lateGpuResultsCannotContaminateNewSamplingWindow() {
        Diagnostics.setProfileMode("all");
        long oldEpoch = Diagnostics.gpuEpoch();
        Diagnostics.onGpuTimestamp(oldEpoch, 0, 12, 24);
        assertEquals(1, Diagnostics.snapshot().gpuRaySamples());
        Diagnostics.resetPerf();
        Diagnostics.onGpuTimestamp(oldEpoch, 0, 12, 24);
        assertEquals(0, Diagnostics.snapshot().gpuRaySamples());
        Diagnostics.onGpuTimestamp(Diagnostics.gpuEpoch(), 1, 8, 16);
        assertEquals(16, Diagnostics.snapshot().gpuTlasNanos());
        Diagnostics.setProfileMode("cpu");
        Diagnostics.onGpuTimestamp(-1, 0, 12, 24);
        assertEquals(0, Diagnostics.snapshot().gpuRaySamples());
        assertThrows(IllegalArgumentException.class, () -> Diagnostics.setProfileMode("invalid"));
        assertEquals("cpu", Diagnostics.snapshot().profileMode());
    }

    @Test
    void timestampsRespectQueueValidBitsAndWrap() {
        assertEquals(10, GpuTimestamps.elapsedTicks(250, 4, 8));
        assertEquals(15, GpuTimestamps.elapsedTicks(0xFFFF_FFFAL, 9, 32));
        assertEquals(8, GpuTimestamps.elapsedTicks(Long.MAX_VALUE - 3, Long.MIN_VALUE + 4, 64));
        assertEquals(0, GpuTimestamps.elapsedTicks(50, 40, 64));
        assertThrows(IllegalArgumentException.class, () -> GpuTimestamps.elapsedTicks(0, 0, 0));
    }
}
