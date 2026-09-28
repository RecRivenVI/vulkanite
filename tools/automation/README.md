# Vulkanite unattended automation (Phase A)

See design notes in the conversation / this README. Modes:

- **Functional** — Win32 desktop isolation (`WinSta0\VulkaniteAuto-<run_id>`), audit IPC, lifecycle counters. Preflight may run with incomplete GPU telemetry.
- **Benchmark** — separate calibration required before treating FPS/frame-time as official. Full preflight GPU+CPU+RAM+disk gates. Not implemented in Phase A.

## Layout

```
tools/automation/
  bin/           PowerShell drivers
  harness/       vulkanite-audit (dev-only Fabric mod)
  config/        contracts (to be filled after baselines)
run/ or E:\Minecraft\PrismLauncherDev\.agent-runs\<run_id>\
```

## Isolation

Functional automation launches **the entire Prism → JVM chain** on a dedicated Win32 desktop created per run. Interactive user keyboard/mouse cannot reach that desktop. Game control uses `vulkanite-audit` TCP on `127.0.0.1` with a random token. Normal Vulkanite does not open the server.

Focus/minimize/size monitoring remains a **contract check**, not the primary isolation mechanism.

## Empty-section PASS (final state)

- confirmed empty mesh update accepted
- no leftover terrain TLAS instance for the target origin after clear
- no late BLAS republish without a place
- re-place produces a new revision/geometry
- `removeSection` false with no holder is **not** a FAIL by itself

## Preflight tiers

- functional: instance lock, CPU/RAM/commit, competing Java
- benchmark: adds reliable GPU utilization/VRAM/temp/power (NVML or Windows GPU counters); BLOCK benchmark if GPU telemetry is missing

## Phase A checklist

1. Auto instance
2. Win32 desktop / input isolation verification
3. IPC audit + Diagnostics snapshot
4. empty-section E2E
5. auto exit + evidence

No product rendering changes beyond read-only `Diagnostics` counters.
