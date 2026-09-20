param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    if (-not $JavaHome) { throw 'Set JAVA_HOME to a Java 25 installation.' }
    $java = Join-Path $JavaHome 'bin/java.exe'
    $classpathFile = Join-Path $repo 'build/loom-cache/argFiles/runClient'
    if (-not (Test-Path $classpathFile)) { throw 'Run Gradle configureClientLaunch first to generate the runtime classpath.' }
    $classpath = (Get-Content $classpathFile)[1]
    foreach ($test in 'ValidateEntityVertices.java', 'ValidateDynamicGeometry.java', 'ValidateTerrainLayers.java', 'ValidateTerrainMaterials.java', 'ValidateReconstruction.java', 'ValidateSharc.java', 'ValidateFoundation.java', 'ProbeVisibilityRaster.java') {
        & $java '-Dorg.lwjgl.system.stackSize=8192' --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp $classpath (Join-Path $PSScriptRoot $test)
        if ($LASTEXITCODE -ne 0) { throw "Validation failed: $test" }
    }
} finally { Pop-Location }
