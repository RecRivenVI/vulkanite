package me.cortex.vulkanite.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenGlTimingsTest {
    private static final class Driver implements OpenGlTimings.Driver {
        int creates, reads, deletes, next;
        boolean ready;
        long clock;
        int bits = 64;
        final HashMap<Integer, Long> times = new HashMap<>();

        public int counterBits() {
            return bits;
        }

        public int[] create() {
            creates++;
            return new int[] {++next, ++next, ++next};
        }

        public void timestamp(int query) {
            clock += 100;
            times.put(query, bits == 64 ? clock : clock & (-1L >>> (64 - bits)));
        }

        public boolean available(int query) {
            return ready;
        }

        public long result(int query) {
            assertTrue(ready, "Reading an unavailable GL query could stall the renderer");
            reads++;
            return times.get(query);
        }

        public void delete(int[] queries) {
            deletes++;
        }
    }

    @AfterEach
    void restoreMode() {
        Diagnostics.setProfileMode("off");
    }

    @Test
    void disabledDiagnosticsAllocateNoQueries() {
        Diagnostics.setProfileMode("off");
        var driver = new Driver();
        try (var timings = new OpenGlTimings(driver)) {
            for (int i = 0; i < 100; i++) {
                timings.beginScene();
                timings.beginHandoff();
                timings.endHandoff();
            }
        }
        assertEquals(0, driver.creates);
        assertEquals(0, driver.reads);
    }

    @Test
    void pendingQueriesNeverBlockAndAllocationIsBounded() {
        Diagnostics.setProfileMode("gpu");
        var driver = new Driver();
        try (var timings = new OpenGlTimings(driver)) {
            for (int i = 0; i < Diagnostics.gpuInterval() * 12; i++) {
                timings.beginScene();
                timings.beginHandoff();
                timings.endHandoff();
            }
            assertEquals(8, driver.creates);
            assertEquals(0, driver.reads);
            assertTrue(Diagnostics.snapshot().gpuSamplesSkipped() > 0);
        }
        assertEquals(8, driver.deletes);
    }

    @Test
    void completedQueriesReportBothScopesAndCanBeReused() {
        Diagnostics.setProfileMode("gpu");
        var driver = new Driver();
        try (var timings = new OpenGlTimings(driver)) {
            timings.beginScene();
            timings.beginHandoff();
            timings.endHandoff();
            driver.ready = true;
            timings.beginScene();
            assertEquals(100, Diagnostics.snapshot().gpuGlSceneNanos());
            assertEquals(100, Diagnostics.snapshot().gpuGlHandoffNanos());
            assertEquals(1, Diagnostics.snapshot().gpuGlHandoffSamples());
            assertEquals(3, driver.reads);
            for (int i = 0; i < Diagnostics.gpuInterval(); i++) timings.beginScene();
            assertEquals(1, driver.creates);
        }
    }

    @Test
    void resetDiscardsOldResultsWithoutReadingOrReusingPendingQueries() {
        Diagnostics.setProfileMode("gpu");
        var driver = new Driver();
        try (var timings = new OpenGlTimings(driver)) {
            timings.beginScene();
            timings.beginHandoff();
            timings.endHandoff();
            Diagnostics.resetPerf();
            timings.beginScene();
            assertEquals(2, driver.creates);
            assertEquals(0, driver.reads);
            driver.ready = true;
            timings.beginScene();
            assertEquals(0, driver.reads);
            assertEquals(0, Diagnostics.snapshot().gpuGlHandoffSamples());
        }
    }

    @Test
    void abandonedProducerFramesNeverReportAnIncompleteHandoff() {
        Diagnostics.setProfileMode("gpu");
        var driver = new Driver();
        driver.ready = true;
        try (var timings = new OpenGlTimings(driver)) {
            timings.beginScene();
            timings.beginScene();
            assertEquals(0, driver.reads);
            assertEquals(0, Diagnostics.snapshot().gpuGlSceneSamples());
        }
    }

    @Test
    void timestampsRespectTheActualGlCounterWidth() {
        Diagnostics.setProfileMode("gpu");
        var driver = new Driver();
        driver.bits = 30;
        driver.clock = (1L << 30) - 180;
        try (var timings = new OpenGlTimings(driver)) {
            timings.beginScene();
            timings.beginHandoff();
            timings.endHandoff();
            driver.ready = true;
            timings.beginScene();
            assertEquals(100, Diagnostics.snapshot().gpuGlSceneNanos());
            assertEquals(100, Diagnostics.snapshot().gpuGlHandoffNanos());
        }
    }
}
