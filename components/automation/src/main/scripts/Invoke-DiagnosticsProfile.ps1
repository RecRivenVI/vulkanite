# Visible, isolated profiling client. All generated state stays within this validation.
param(
    [string]$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..\..')).Path,
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [string]$RunId = 'diagnostics',
    [int]$AuditPort = 53317,
    [string]$WorldName = 'auto-empty-section',
    [switch]$LeaveRunning
)
$ErrorActionPreference = 'Stop'
$root = Join-Path $RepoRoot 'validations\performance-diagnostics'
$instanceRoot = Join-Path $root 'instance'
$resultRoot = Join-Path $root 'result'
$mcDir = Join-Path $root 'instance\26.3-fabric\client'
$resultDir = Join-Path $root "result\$RunId"
New-Item -ItemType Directory -Force -Path $mcDir | Out-Null
$lock = [System.IO.File]::Open((Join-Path $instanceRoot 'instance.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$token = 'tk-' + [guid]::NewGuid().ToString('N')
$launcher = $null
$ready = $false
$success = $false
$initScript = $null
function Invoke-Audit([string]$Command) {
    $connection = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection.Connect('127.0.0.1', $AuditPort)
        $stream = $connection.GetStream()
        $stream.ReadTimeout = 15000
        $writer = [System.IO.StreamWriter]::new($stream)
        $writer.AutoFlush = $true
        $reader = [System.IO.StreamReader]::new($stream)
        $parts = $Command -split ' ', 2
        $writer.WriteLine("$($parts[0]) $token $($parts[1])")
        $response = $reader.ReadLine() | ConvertFrom-Json
        if (-not $response.ok) { throw "Probe failed: $($response | ConvertTo-Json -Compress)" }
        return $response
    } finally { $connection.Dispose() }
}
try {
    $existing = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($mcDir, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
    if ($existing.Count) { throw "Validation instance already running: $($existing.ProcessId -join ',')" }
    # Hold the instance lock through result replacement and evidence publication.
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($resultDir)) -ne [IO.Path]::GetFullPath($resultRoot)) {
        throw 'The result directory must be directly inside this validation result root'
    }
    if (Test-Path -LiteralPath $resultRoot) {
        if ((Get-Item -LiteralPath $resultRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'The result root must not be a reparse point'
        }
    }
    if (Test-Path -LiteralPath $resultDir) {
        $previousResult = Get-Item -LiteralPath $resultDir -Force
        if (-not $previousResult.PSIsContainer -or ($previousResult.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            @(Get-ChildItem -LiteralPath $resultDir -Force -Recurse | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
            throw 'The previous result must be a directory without reparse points'
        }
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($previousResult.FullName, 'OnlyErrorDialogs', 'SendToRecycleBin', 'ThrowException')
    }
    New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
    $source = Join-Path $RepoRoot 'validations\integration-empty_section\instance\26.3-fabric\client'
    if ([IO.Path]::GetFileName($WorldName) -ne $WorldName -or $WorldName -in @('.', '..')) { throw 'WorldName must be a save folder name' }
    $world = Join-Path $mcDir "saves\$WorldName"
    if (-not (Test-Path (Join-Path $world 'level.dat'))) {
        if ($WorldName -ne 'auto-empty-section') { throw "Missing isolated world: $WorldName" }
        if (-not (Test-Path (Join-Path $source 'saves\auto-empty-section\level.dat'))) { throw 'Missing isolated world fixture' }
        New-Item -ItemType Directory -Force -Path (Split-Path $world) | Out-Null
        Copy-Item -LiteralPath (Join-Path $source 'saves\auto-empty-section') -Destination $world -Recurse
    }
    $options = Join-Path $mcDir 'options.txt'
    if (-not (Test-Path $options)) { Copy-Item -LiteralPath (Join-Path $source 'options.txt') -Destination $options }
    $settings = @(Get-Content -LiteralPath $options | Where-Object { $_ -notmatch '^(enableVsync|maxFps|pauseOnLostFocus|inactivityFpsLimit):' })
    $settings = @($settings + 'enableVsync:false' + 'maxFps:260' + 'pauseOnLostFocus:false' + 'inactivityFpsLimit:"minimized"')
    $settings | Set-Content -LiteralPath $options -Encoding utf8NoBOM
    $config = Join-Path $mcDir 'config'
    $packs = Join-Path $mcDir 'shaderpacks'
    New-Item -ItemType Directory -Force -Path $config, $packs | Out-Null
    $pack = Join-Path $RepoRoot 'components\foundation_pack\build\distributions\Vulkanite-Foundation.zip'
    Copy-Item -LiteralPath $pack -Destination $packs -Force
    @('enableShaders=true', 'shaderPack=Vulkanite-Foundation.zip') | Set-Content -LiteralPath (Join-Path $config 'iris.properties') -Encoding utf8NoBOM
    $version = ((Get-Content (Join-Path $RepoRoot 'gradle.properties') | Where-Object { $_ -match '^mod_version=' }) -split '=', 2)[1]
    $jar = Join-Path $RepoRoot "versions\26.3-fabric\build\libs\vulkanite-26.3-fabric-$version.jar"
    $manifest = [ordered]@{
        git_head = (git -C $RepoRoot rev-parse HEAD).Trim()
        git_dirty = @(git -C $RepoRoot status --porcelain)
        artifact_sha256 = (Get-FileHash -LiteralPath $jar).Hash
        pack_sha256 = (Get-FileHash -LiteralPath $pack).Hash
        options = $settings
        game_directory = $mcDir
        world = $WorldName
        audit_port = $AuditPort
        audit_token = $token
        started = (Get-Date).ToString('o')
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $resultDir 'manifest.json') -Encoding utf8NoBOM
    $oldOptions = $env:JAVA_TOOL_OPTIONS
    $oldWorld = $env:VULKANITE_PROFILE_WORLD
    try {
        $env:JAVA_TOOL_OPTIONS = "$oldOptions -Dvulkanite.audit.enable=true -Dvulkanite.audit.token=$token -Dvulkanite.audit.port=$AuditPort"
        $env:VULKANITE_PROFILE_WORLD = $WorldName
        $initScript = Join-Path $instanceRoot 'profile-init.gradle'
        @'
gradle.projectsEvaluated {
    def target = rootProject.findProject(':version:26.3-fabric')
    if (target == null) return
    target.extensions.getByName('loom').runs.configureEach { run ->
        if (run.name == 'validationPerformanceDiagnosticsClient') {
            def launchArgs = new ArrayList(run.programArguments.get())
            def index = launchArgs.indexOf('--quickPlaySingleplayer')
            if (index < 0 || index + 1 >= launchArgs.size()) throw new GradleException('Missing validation quick-play argument')
            launchArgs.set(index + 1, System.getenv('VULKANITE_PROFILE_WORLD'))
            run.programArguments.set(launchArgs)
        }
    }
}
'@ | Set-Content -LiteralPath $initScript -Encoding utf8NoBOM
        $gradle = Join-Path $RepoRoot 'gradlew.bat'
        $consoleLog = Join-Path $resultDir 'gradle-console.log'
        $launcher = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', 'call', "`"$gradle`"", ':version:26.3-fabric:runValidationPerformanceDiagnosticsClient', '--init-script', "`"$initScript`"", '--no-configuration-cache', '--console=plain', '--no-daemon', '>', "`"$consoleLog`"", '2>&1') -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru
    } finally { $env:JAVA_TOOL_OPTIONS = $oldOptions; $env:VULKANITE_PROFILE_WORLD = $oldWorld }
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline -and -not $launcher.HasExited) {
        try {
            $snapshot = (Invoke-Audit 'snapshot').data
            if ($snapshot.frameCount -gt 10) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Client did not reach a running PT world' }
    $sampler = Join-Path $PSScriptRoot 'bench_sample.py'
    foreach ($mode in @('off', 'cpu', 'all', 'off', 'all', 'off')) {
        $index = @(Get-ChildItem $resultDir -Filter '*-sample.json').Count
        $out = Join-Path $resultDir "$index-$mode-sample.json"
        & python $sampler $token $AuditPort $out --mode $mode
        if ($LASTEXITCODE -ne 0) { throw "Sampling failed: $mode" }
    }
    Invoke-Audit 'profile all' | Out-Null
    if ($WorldName -ne 'auto-empty-section') { Start-Sleep -Seconds 3 }
    foreach ($block in $(if ($WorldName -eq 'auto-empty-section') { @('minecraft:air', 'minecraft:stone', 'minecraft:air', 'minecraft:stone') } else { @() })) {
        Invoke-Audit "setblock 8 70 8 $block" | Out-Null
        Start-Sleep -Seconds 2
    }
    $coverage = (Invoke-Audit 'snapshot').data
    $coverage | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $resultDir 'coverage.json') -Encoding utf8NoBOM
    $samples = @(Get-ChildItem $resultDir -Filter '*-sample.json' | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json })
    $offValid = @($samples | Where-Object { $_.profile_mode -eq 'off' -and @($_.snapshot_end.PSObject.Properties | Where-Object { $_.Name -match '^cpu.*Nanos$|^gpu.*Samples$' -and $_.Value -ne 0 }).Count -gt 0 }).Count -eq 0
    $unlimited = @($samples | Where-Object { -not $_.throughput_comparison_valid }).Count -eq 0
    $terrainValid = $WorldName -ne 'auto-empty-section' -or $coverage.gpuTerrainBlasSamples -gt 0
    $pass = $offValid -and $unlimited -and $coverage.gpuRaySamples -gt 0 -and $coverage.gpuTlasSamples -gt 0 -and $coverage.gpuGlSceneSamples -gt 0 -and $coverage.gpuGlHandoffSamples -gt 0 -and $terrainValid -and $coverage.gpuQueriesFailed -eq 0
    @{ status = $(if ($pass) { 'PASS' } else { 'FAIL' }); coverage = $coverage; samples = $samples } | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $resultDir 'result.json') -Encoding utf8NoBOM
    if (-not $pass) { throw 'Profiling coverage or disabled-mode isolation failed' }
    $success = $true
    Write-Output "PASS: $resultDir"
} finally {
    if ($ready) {
        try { Invoke-Audit 'profile off' | Out-Null } catch { }
        if (-not $LeaveRunning -or -not $success) {
            try { Invoke-Audit 'quit' | Out-Null } catch { }
            if ($launcher -and -not $launcher.WaitForExit(60000)) { Write-Warning 'Own client has not exited yet; no unrelated process was terminated.' }
        } else { Write-Output "Client left running: $mcDir" }
    }
    $lock.Dispose()
}
