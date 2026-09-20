val requiredScripts =
    listOf(
        "src/main/scripts/Invoke-EmptySectionE2E.ps1",
        "src/main/scripts/Preflight.ps1",
        "src/main/scripts/bench_sample.py",
        "src/main/scripts/Invoke-DiagnosticsProfile.ps1",
    )

tasks.named("check") {
    dependsOn("testSampling")
    group = "verification"
    description = "Checks the automation scripts distributed with the validation tool."
    inputs.files(requiredScripts.map { layout.projectDirectory.file(it) })
    doLast {
        val missing = inputs.files.files.filterNot { it.isFile }
        check(missing.isEmpty()) { "Missing automation scripts: $missing" }
    }
}

tasks.register<Exec>("testSampling") {
    group = "verification"
    commandLine("python", "-m", "unittest", "discover", "-s", "src/test/scripts")
    workingDir(layout.projectDirectory)
}

tasks.register<Exec>("profileDiagnostics") {
    group = "verification"
    description = "Runs isolated diagnostic overhead and GPU timing validation."
    commandLine(
        "pwsh",
        "-NoProfile",
        "-File",
        layout.projectDirectory.file("src/main/scripts/Invoke-DiagnosticsProfile.ps1").asFile,
    )
    workingDir(layout.projectDirectory.dir("../.."))
    if (providers.gradleProperty("leaveRunning").map(String::toBoolean).getOrElse(false)) {
        args("-LeaveRunning")
    }
}
