# Empty-section validation in the template-managed, disposable instance.
param(
    [string]$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..\..')).Path,
    [string]$RunId = ('empty-section-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$AuditPort = 53217,
    [int]$BlockX = 8, [int]$BlockY = 70, [int]$BlockZ = 8,
    [int]$GameWaitSeconds = 180
)
$ErrorActionPreference = 'Stop'
$script:fail = $null
$validationRoot = Join-Path $RepoRoot 'validations\integration-empty_section'
$runDir = Join-Path $validationRoot "result\$RunId"
$mcDir = Join-Path $validationRoot 'instance\26.3-fabric\client'
New-Item -ItemType Directory -Force -Path $runDir, (Join-Path $runDir 'logs'), (Join-Path $runDir 'screenshots'), (Join-Path $runDir 'crash') | Out-Null

$world = 'auto-empty-section'
$worldFile = Join-Path $mcDir "saves\$world\level.dat"
if (-not (Test-Path -LiteralPath $worldFile -PathType Leaf)) {
    throw "The isolated validation world is missing: $worldFile"
}
$packSource = Join-Path $RepoRoot 'components\foundation_pack\build\distributions\Vulkanite-Foundation.zip'
if (-not (Test-Path -LiteralPath $packSource -PathType Leaf)) {
    throw "Build the Foundation pack before validation: $packSource"
}
$shaderpacksDir = Join-Path $mcDir 'shaderpacks'
$configDir = Join-Path $mcDir 'config'
New-Item -ItemType Directory -Force -Path $shaderpacksDir, $configDir | Out-Null
Copy-Item -LiteralPath $packSource -Destination (Join-Path $shaderpacksDir 'Vulkanite-Foundation.zip') -Force
$irisConfig = Join-Path $configDir 'iris.properties'
$otherIrisSettings = @()
if (Test-Path -LiteralPath $irisConfig) {
    $otherIrisSettings = @(Get-Content -LiteralPath $irisConfig | Where-Object { $_ -notmatch '^\s*(enableShaders|shaderPack)\s*=' })
}
@($otherIrisSettings + 'enableShaders=true' + 'shaderPack=Vulkanite-Foundation.zip') |
    Set-Content -LiteralPath $irisConfig -Encoding utf8NoBOM

$token = 'tk-' + [guid]::NewGuid().ToString('N')

# --- git / artifact identity ---
$head = (git -C $RepoRoot rev-parse HEAD).Trim()
$dirty = (git -C $RepoRoot status --porcelain)
$diffTmp = Join-Path $runDir 'diff.tmp'
if ($dirty) { (git -C $RepoRoot diff) | Set-Content -LiteralPath $diffTmp -Encoding UTF8 } else { '' | Set-Content -LiteralPath $diffTmp -Encoding UTF8 }
$diffHash = if ($dirty) { (Get-FileHash -LiteralPath $diffTmp -Algorithm SHA256).Hash } else { 'clean' }
$versionLine = Get-Content -LiteralPath (Join-Path $RepoRoot 'gradle.properties') | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
if (-not $versionLine) { throw 'Missing mod_version in gradle.properties' }
$modVersion = $versionLine.Substring('mod_version='.Length)
$jar = Join-Path $RepoRoot "versions\26.3-fabric\build\libs\vulkanite-26.3-fabric-$modVersion.jar"
$jarSha = (Get-FileHash $jar -Algorithm SHA256).Hash
$pack = Join-Path $mcDir 'shaderpacks\Vulkanite-Foundation.zip'
$packSha = if (Test-Path $pack) { (Get-FileHash $pack -Algorithm SHA256).Hash } else { $null }

$manifest = [ordered]@{
    run_id = $RunId
    scenario = 'empty-section'
    mode = 'functional'
    git_head = $head
    git_dirty = [bool]$dirty
    diff_hash = $diffHash
    automation_jar_sha256 = $jarSha
    shader_pack_sha256 = $packSha
    run_directory = $runDir
    game_directory = $mcDir
    world = $world
    audit = @{ port = $AuditPort; token_id = 'per-run'; endpoint = "127.0.0.1:$AuditPort" }
    target_block = @{ x = $BlockX; y = $BlockY; z = $BlockZ }
    started = (Get-Date).ToString('o')
    pass_criteria = @(
        'non-empty baseline accepted'
        'confirmed empty update accepted after STONE->AIR'
        'no stale BLAS republish (blasRejected may rise; published must not restore old without place)'
        'AIR->STONE yields new non-empty revision'
        'rapid toggle ends consistent with last block state'
    )
    coverage_gaps = @('The aggregate section and TLAS counters cannot establish per-origin holder uniqueness.')
}

function Invoke-Audit([string]$line) {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', $AuditPort)
    $s = $client.GetStream()
    $w = New-Object System.IO.StreamWriter($s); $w.AutoFlush = $true
    $r = New-Object System.IO.StreamReader($s)
    $w.WriteLine($line)
    $resp = $r.ReadLine()
    $client.Close()
    return $resp
}
function Assert([string]$name, [bool]$cond, [string]$detail) {
    $script:steps += @{ name = $name; status = $(if ($cond) { 'PASS' } else { 'FAIL' }); pass = $cond; detail = $detail }
    if (-not $cond -and -not $script:fail) { $script:fail = "$name :: $detail" }
}
function Record-Unmeasured([string]$name, [string]$detail) {
    $script:steps += @{ name = $name; status = 'NOT_MEASURED'; detail = $detail }
}
$script:steps = @()

function Get-ExistingValidationGames {
    @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($mcDir, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
}

function Get-OwnedProcessSnapshot {
    if (-not $launcher) { return @() }
    $all = @(Get-CimInstance Win32_Process)
    $owned = [System.Collections.Generic.HashSet[int]]::new()
    [void]$owned.Add($launcher.Id)
    do {
        $before = $owned.Count
        foreach ($candidate in $all) {
            if ($owned.Contains([int]$candidate.ParentProcessId) -and $candidate.CreationDate -ge $launchStarted.AddSeconds(-2)) {
                [void]$owned.Add([int]$candidate.ProcessId)
            }
        }
    } while ($owned.Count -gt $before)
    @($all | Where-Object { $owned.Contains([int]$_.ProcessId) })
}

function Get-ValidationGameProcesses {
    @(Get-OwnedProcessSnapshot | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and
        $_.CommandLine -and
        $_.CommandLine.IndexOf($mcDir, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
}

function Get-RemainingOwnedProcesses {
    @(Get-OwnedProcessSnapshot | Where-Object {
        $_.ProcessId -ne $launcher.Id -and
        $_.CommandLine -notmatch 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Stop-ValidationProcessTree {
    $errors = [System.Collections.Generic.List[string]]::new()
    $stopped = @()
    try { $candidates = @(Get-OwnedProcessSnapshot | Sort-Object CreationDate -Descending) }
    catch { return @("Owned process snapshot failed: $($_.Exception.Message)") }
    foreach ($candidate in $candidates) {
        if ($candidate.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon') { continue }
        try {
            $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($candidate.ProcessId)" -ErrorAction Stop
            if (-not $current -or $current.CreationDate -ne $candidate.CreationDate) { continue }
            $process = Get-Process -Id $candidate.ProcessId -ErrorAction Stop
            Stop-Process -Id $candidate.ProcessId -Force -ErrorAction Stop
            $stopped += $process
        } catch {
            $errors.Add("Stop PID $($candidate.ProcessId): $($_.Exception.Message)")
        }
    }
    foreach ($process in $stopped) {
        try {
            if (-not $process.WaitForExit(15000)) {
                $errors.Add("Wait PID $($process.Id): timed out")
            }
        } catch {
            $errors.Add("Wait PID $($process.Id): $($_.Exception.Message)")
        }
    }
    return $errors.ToArray()
}

function Close-ValidationRun([int]$GraceMilliseconds = 90000, [int]$RetryWaitMilliseconds = 15000) {
    if (-not $launcher) { return }
    $errors = [System.Collections.Generic.List[string]]::new()
    try { Invoke-Audit "quit $token" | Out-Null } catch { }
    try { $null = $launcher.WaitForExit($GraceMilliseconds) }
    catch { $errors.Add("Initial launcher wait: $($_.Exception.Message)") }

    $forced = $false
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        try {
            $games = @(Get-ExistingValidationGames)
            $remainingOwned = @(Get-RemainingOwnedProcesses)
            $launcherExited = $launcher.HasExited
            if ($launcherExited -and $games.Count -eq 0 -and $remainingOwned.Count -eq 0) { break }
        } catch {
            $errors.Add("Cleanup snapshot $attempt`: $($_.Exception.Message)")
        }
        $forced = $true
        foreach ($errorText in @(Stop-ValidationProcessTree)) { $errors.Add($errorText) }
        try { $null = $launcher.WaitForExit($RetryWaitMilliseconds) }
        catch { $errors.Add("Launcher wait $attempt`: $($_.Exception.Message)") }
        try {
            foreach ($game in @(Get-ValidationGameProcesses)) {
                $process = Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue
                if ($process -and -not $process.WaitForExit($RetryWaitMilliseconds)) {
                    $errors.Add("Game wait PID $($game.ProcessId): timed out")
                }
            }
        } catch { $errors.Add("Game wait $attempt`: $($_.Exception.Message)") }
    }

    # Keep the exclusive instance lock until both the launcher and this game directory are clear.
    # A failed query is not evidence of process exit.
    $reportedResidual = $false
    while ($true) {
        try {
            $games = @(Get-ExistingValidationGames)
            $remainingOwned = @(Get-RemainingOwnedProcesses)
            $launcherExited = $launcher.HasExited
            $manifest.launcher_exit_confirmed = $launcherExited
            $manifest.remaining_validation_game_pids = @($games | ForEach-Object { $_.ProcessId })
            $manifest.remaining_owned_pids = @($remainingOwned | ForEach-Object { $_.ProcessId })
            if ($launcherExited -and $games.Count -eq 0 -and $remainingOwned.Count -eq 0) { break }
        } catch {
            $errors.Add("Final process snapshot: $($_.Exception.Message)")
            $launcherExited = $false
        }
        if (-not $reportedResidual) {
            $manifest.status = 'BLOCKED'
            $manifest.reason = 'Validation process still active after bounded cleanup; retaining the instance lock until exit'
            $manifest.cleanup_errors = $errors.ToArray()
            $manifest | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
            $reportedResidual = $true
        }
        try { $null = $launcher.WaitForExit($RetryWaitMilliseconds) }
        catch { $errors.Add("Residual launcher wait: $($_.Exception.Message)") }
        Start-Sleep -Milliseconds $RetryWaitMilliseconds
    }

    $manifest.launcher_exit_code = $launcher.ExitCode
    $manifest.cleanup_errors = $errors.ToArray()
    if ($forced -or $errors.Count -gt 0) {
        $manifest.status = 'BLOCKED'
        $manifest.reason = "Validation required forced cleanup or encountered cleanup errors: $($errors -join '; ')"
    } elseif ($manifest.status -eq 'PASS' -and $launcher.ExitCode -ne 0) {
        $manifest.status = 'FAIL'
        $manifest.reason = "Validation launcher exited with code $($launcher.ExitCode)"
    }
}

# --- preflight (functional) ---
$pref = & (Join-Path $PSScriptRoot 'Preflight.ps1') -Mode functional | ConvertFrom-Json
$pref | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDir 'preflight.json') -Encoding UTF8
$manifest.preflight_status = $pref.status
if ($pref.status -eq 'BLOCKED') {
    $manifest.status = 'BLOCKED'
    $manifest.reason = ($pref.reasons -join '; ')
    $manifest | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
    @{ status = 'BLOCKED'; reason = $manifest.reason } | ConvertTo-Json | Set-Content (Join-Path $runDir 'result.json') -Encoding UTF8
    exit 2
}

# --- launch the template validation task ---
$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS

$gradle = Join-Path $RepoRoot 'gradlew.bat'
$gradleTask = ':version:26.3-fabric:runValidationIntegrationEmptySectionClient'
$command = $env:ComSpec
if (-not $command) { $command = Join-Path $env:WINDIR 'System32\cmd.exe' }
$gradleConsoleLog = Join-Path $runDir 'gradle-console.log'
$launchArguments = @('/d', '/c', 'call', $gradle, $gradleTask, '--console=plain', '--no-daemon', '>', $gradleConsoleLog, '2>&1')
$launcher = $null
$launchStarted = Get-Date
$instanceLock = $null
try {
$instanceLock = [System.IO.File]::Open((Join-Path $validationRoot 'result\instance.lock'), [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
$existingGames = @(Get-ExistingValidationGames)
if ($existingGames.Count -gt 0) {
    throw "Validation game directory is already in use by PID(s): $($existingGames.ProcessId -join ',')"
}
try {
    $env:JAVA_TOOL_OPTIONS = ((@($oldJavaToolOptions, '-Dvulkanite.audit.enable=true', "-Dvulkanite.audit.token=$token", "-Dvulkanite.audit.port=$AuditPort") | Where-Object { $_ }) -join ' ')
    $launcher = Start-Process -FilePath $command -ArgumentList $launchArguments -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru
} finally {
    $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
}
$manifest.gradle_pid = $launcher.Id
$manifest.audit_token_fingerprint = $token.Substring(0, 8)
$manifest.audit_port = $AuditPort

$processJson = [ordered]@{
    pid = $launcher.Id
    command = "$command /d /c gradlew.bat $gradleTask"
    created = [DateTime]::Now.ToString("o")
}
$processJson | ConvertTo-Json | Set-Content (Join-Path $runDir 'process.json') -Encoding UTF8

# --- wait audit ---
$up = $false
$deadline = (Get-Date).AddSeconds($GameWaitSeconds)
while ((Get-Date) -lt $deadline) {
    try {
        $pong = Invoke-Audit "ping $token"
        if ($pong -match '"ok"\s*:\s*true') { $up = $true; break }
    } catch { }
    if ($launcher.HasExited) { break }
    Start-Sleep -Seconds 1
}
$manifest.audit_up = $up
if (-not $up) {
    throw 'Audit handshake failed / game not ready'
}

try {
    # settle + baseline place
    Start-Sleep -Seconds 3
    $s0 = (Invoke-Audit "snapshot $token") | ConvertFrom-Json
    Invoke-Audit "setblock $token $BlockX $BlockY $BlockZ minecraft:stone" | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    $base = $null
    while ((Get-Date) -lt $deadline) {
        $base = (Invoke-Audit "snapshot $token") | ConvertFrom-Json
        if ($base.ok -and $base.data.nonEmptyMeshUpdates -ge 1) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert 'baseline_nonempty' ($base -and $base.data.nonEmptyMeshUpdates -ge 1) ($base | ConvertTo-Json -Compress)
    Assert 'generation_recorded' ($base -and $base.data.activeGeneration -ge 0) ("gen=" + $base.data.activeGeneration)
    Assert 'blas_enqueued' ($base -and $base.data.blasEnqueued -ge 1) ("enq=" + $base.data.blasEnqueued)

    # STONE -> AIR
    $beforeEmpty = $base.data
    Invoke-Audit "setblock $token $BlockX $BlockY $BlockZ minecraft:air" | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    $empty = $null
    while ((Get-Date) -lt $deadline) {
        $empty = (Invoke-Audit "snapshot $token") | ConvertFrom-Json
        if ($empty.ok -and $empty.data.emptyMeshUpdates -gt $beforeEmpty.emptyMeshUpdates) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert 'empty_update_accepted' ($empty -and $empty.data.emptyMeshUpdates -gt $beforeEmpty.emptyMeshUpdates) ($empty | ConvertTo-Json -Compress)
    Record-Unmeasured 'origin_holder_uniqueness' ("Aggregate sections=" + $empty.data.currentSections + " TLAS instances=" + $empty.data.tlasInstances + '; no per-origin identity is exposed by this probe')
    # stability: no nonempty increase for ~2s without place
    $stab = $empty.data.nonEmptyMeshUpdates
    Start-Sleep -Seconds 2
    $empty2 = (Invoke-Audit "snapshot $token") | ConvertFrom-Json
    Assert 'no_stale_republish' ($empty2.data.nonEmptyMeshUpdates -eq $stab) ("nonEmpty $stab -> " + $empty2.data.nonEmptyMeshUpdates)

    # AIR -> STONE
    Invoke-Audit "setblock $token $BlockX $BlockY $BlockZ minecraft:stone" | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    $re = $null
    while ((Get-Date) -lt $deadline) {
        $re = (Invoke-Audit "snapshot $token") | ConvertFrom-Json
        if ($re.ok -and $re.data.nonEmptyMeshUpdates -gt $empty2.data.nonEmptyMeshUpdates) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert 'replace_nonempty' ($re -and $re.data.nonEmptyMeshUpdates -gt $empty2.data.nonEmptyMeshUpdates) ($re | ConvertTo-Json -Compress)

    # rapid toggle x5
    $prevNonEmpty = $re.data.nonEmptyMeshUpdates
    for ($i = 0; $i -lt 5; $i++) {
        Invoke-Audit "setblock $token $BlockX $BlockY $BlockZ minecraft:air" | Out-Null
        Start-Sleep -Milliseconds 300
        Invoke-Audit "setblock $token $BlockX $BlockY $BlockZ minecraft:stone" | Out-Null
        Start-Sleep -Milliseconds 300
    }
    Start-Sleep -Seconds 2
    $final = (Invoke-Audit "snapshot $token") | ConvertFrom-Json
    $final | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDir 'diagnostics.json') -Encoding UTF8
    Assert 'rapid_toggle_no_crash' ($final.ok -eq $true) ($final | ConvertTo-Json -Compress)
    # last action was place -> expect nonempty growth or at least no empty-only orphan
    Assert 'final_consistent' ($final.data.nonEmptyMeshUpdates -ge $prevNonEmpty) ("nonEmpty " + $prevNonEmpty + " -> " + $final.data.nonEmptyMeshUpdates)

    $manifest.result_steps = $script:steps
    $manifest.final_snapshot = $final
    if ($script:fail) { $manifest.status = 'FAIL'; $manifest.reason = $script:fail }
    else { $manifest.status = 'PASS' }
} catch {
    $manifest.status = 'CRASHED'
    $manifest.reason = $_.Exception.Message
}
} catch {
    $manifest.status = 'BLOCKED'
    $manifest.reason = $_.Exception.Message
} finally {
    try { Close-ValidationRun }
    catch {
        $manifest.status = 'BLOCKED'
        $manifest.reason = "Validation cleanup failed: $($_.Exception.Message)"
    } finally {
        if ($launcher) {
            $confirmedExited = $false
            while (-not $confirmedExited) {
                try {
                    $games = @(Get-ExistingValidationGames)
                    $remainingOwned = @(Get-RemainingOwnedProcesses)
                    $confirmedExited = $launcher.HasExited -and $games.Count -eq 0 -and $remainingOwned.Count -eq 0
                } catch { $confirmedExited = $false }
                if (-not $confirmedExited) {
                    $manifest.status = 'BLOCKED'
                    $manifest.reason = 'Validation process exit could not be confirmed; retaining the instance lock'
                    Start-Sleep -Seconds 15
                }
            }
        }
        if ($instanceLock) { $instanceLock.Dispose() }
    }
}

if (Test-Path (Join-Path $mcDir 'logs\latest.log')) {
    Copy-Item (Join-Path $mcDir 'logs\latest.log') (Join-Path $runDir 'logs\') -Force
}
if (Test-Path (Join-Path $mcDir 'crash-reports')) {
    Get-ChildItem (Join-Path $mcDir 'crash-reports') -ErrorAction SilentlyContinue | Select-Object -First 5 | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $runDir 'crash\') -Force
    }
}
$manifest.completed = (Get-Date).ToString('o')
$manifest | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
@{ status = $manifest.status; reason = $manifest.reason; steps = $script:steps } | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDir 'result.json') -Encoding UTF8

Write-Host "STATUS=$($manifest.status) RUN=$runDir"
if ($manifest.status -ne 'PASS') { exit 1 }
exit 0
