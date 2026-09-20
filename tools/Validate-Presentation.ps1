param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$java = Join-Path $JavaHome 'bin/java.exe'
$classpath = (Get-Content (Join-Path $repo 'build/loom-cache/argFiles/runClient'))[1]
foreach ($probe in 'ProbeUiComposition.java', 'ProbeVulkanPresentation.java') {
    & $java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp $classpath (Join-Path $PSScriptRoot $probe)
    if ($LASTEXITCODE -ne 0) { throw "Presentation probe failed: $probe" }
}
