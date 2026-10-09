val requiredScripts =
    listOf(
        "src/main/scripts/Invoke-EmptySectionE2E.ps1",
        "src/main/scripts/Preflight.ps1",
        "src/main/scripts/bench_sample.py",
    )

tasks.named("check") {
    group = "verification"
    description = "Checks the automation scripts distributed with the validation tool."
    inputs.files(requiredScripts.map { layout.projectDirectory.file(it) })
    doLast {
        val missing = inputs.files.files.filterNot { it.isFile }
        check(missing.isEmpty()) { "Missing automation scripts: $missing" }
    }
}
