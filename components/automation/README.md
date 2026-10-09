# Automation tool

This tool component owns the empty-section validation driver and its Windows preflight. The game-side audit entrypoint is the separate `vulkanite_probe` mod in `versions/26.3-fabric/src/probe/`; it is loaded only for validation runs. Product diagnostics remain in the client source set.

Run the scenario from the repository root as documented in `validations/integration-empty_section/validation.md`. The driver uses the template-managed disposable instance at `validations/integration-empty_section/instance/` and writes evidence to `validations/integration-empty_section/result/`. It does not use the player's daily instance.

The functional mode checks terrain section removal and replacement through the audit IPC. It reports aggregate section and TLAS counts but cannot infer per-origin uniqueness from them. The optional benchmark preflight only reports whether GPU telemetry is available; it does not establish a performance baseline.
