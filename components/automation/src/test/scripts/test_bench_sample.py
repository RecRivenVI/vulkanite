import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("bench_sample", Path(__file__).parents[2] / "main/scripts/bench_sample.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class SamplingTest(unittest.TestCase):
    def snapshot(self, count, mode="all"):
        return {"frameCount": count, "profileMode": mode, "cpuPipelineNanos": 0,
                **{f"gpu{s}{kind}": 0 for s in ("Ray", "Tlas", "DynBlas", "TerrainBlas") for kind in ("Samples", "Nanos")}}

    def test_fps_does_not_use_bounded_ring_length(self):
        result = bench.summarize(self.snapshot(0), self.snapshot(1500), [10_000_000] * 512, 15)
        self.assertEqual(100, result["pt_callbacks_per_second"])
        self.assertEqual(512, result["frame_interval_sample_count"])
        self.assertIsNone(result["gpu_scopes"]["Tlas"]["mean_ms"])

    def test_disabled_timings_are_unmeasured(self):
        result = bench.summarize(self.snapshot(0, "off"), self.snapshot(100, "off"), [], 1)
        self.assertIsNone(result["cpu_ms_per_pt_frame"])
        self.assertIsNone(result["frame_interval_mean_ms"])

    def test_gpu_uses_actual_sample_count(self):
        end = self.snapshot(500)
        end.update(gpuRaySamples=10, gpuRayNanos=20_000_000)
        result = bench.summarize(self.snapshot(0), end, [], 5)
        self.assertEqual(2, result["gpu_scopes"]["Ray"]["mean_ms"])
        with self.assertRaises(ValueError):
            bench.summarize(end, end, [], 5)

    def test_gl_handoff_keeps_its_own_samples_and_is_not_subtracted_from_ray(self):
        end = self.snapshot(100)
        end.update(gpuRaySamples=4, gpuRayNanos=40_000_000,
                   gpuGlHandoffSamples=2, gpuGlHandoffNanos=30_000_000,
                   gpuGlSceneSamples=3, gpuGlSceneNanos=9_000_000)
        result = bench.summarize(self.snapshot(0), end, [], 1)
        self.assertEqual(15, result["gpu_scopes"]["GlHandoff"]["mean_ms"])
        self.assertEqual(10, result["gpu_scopes"]["Ray"]["mean_ms"])
        self.assertEqual(3, result["gpu_scopes"]["GlScene"]["mean_ms"])


if __name__ == "__main__":
    unittest.main()
