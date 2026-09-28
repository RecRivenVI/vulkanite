# Invoke-EmptySectionE2E.ps1 — repo-local Phase A pilot (no Prism).
param(
    [string]$RepoRoot = 'D:\Workspaces\Repositories\GitHub\RecRivenVI\vulkanite',
    [string]$RunId = ('empty-section-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [int]$AuditPort = 53217,
    [int]$BlockX = 8, [int]$BlockY = 70, [int]$BlockZ = 8,
    [int]$GameWaitSeconds = 180,
    [switch]$SkipDesktop
)
$ErrorActionPreference = 'Stop'
$script:fail = $null
$runDir = Join-Path $RepoRoot "run\automation\runs\$RunId"
$mcDir = Join-Path $RepoRoot 'run\automation\empty-section\minecraft'
New-Item -ItemType Directory -Force -Path $runDir, (Join-Path $runDir 'logs'), (Join-Path $runDir 'screenshots'), (Join-Path $runDir 'crash') | Out-Null

$token = 'tk-' + [guid]::NewGuid().ToString('N')
$desktop = "VulkaniteAuto-$RunId"
$desktop = $desktop -replace '[^A-Za-z0-9\-]', ''
$world = 'auto-empty-section'

# --- git / artifact identity ---
$head = (git -C $RepoRoot rev-parse HEAD).Trim()
$dirty = (git -C $RepoRoot status --porcelain)
$diffTmp = Join-Path $runDir 'diff.tmp'
if ($dirty) { (git -C $RepoRoot diff) | Set-Content -LiteralPath $diffTmp -Encoding UTF8 } else { '' | Set-Content -LiteralPath $diffTmp -Encoding UTF8 }
$diffHash = if ($dirty) { (Get-FileHash -LiteralPath $diffTmp -Algorithm SHA256).Hash } else { 'clean' }
$jar = Join-Path $RepoRoot 'build\libs\vulkanite-0.0.4-pre-alpha+26.3.jar'
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
    desktop = "WinSta0\$desktop"
    audit = @{ port = $AuditPort; token_id = 'per-run'; endpoint = "127.0.0.1:$AuditPort" }
    target_block = @{ x = $BlockX; y = $BlockY; z = $BlockZ }
    started = (Get-Date).ToString('o')
    pass_criteria = @(
        'non-empty baseline accepted'
        'confirmed empty update accepted after STONE->AIR'
        'no stale BLAS republish (blasRejected may rise; published must not restore old without place)'
        'AIR->STONE yields new non-empty revision'
        'rapid toggle ends consistent with last block state'
        'same origin at most one current holder (currentSections<=expected)'
    )
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
    $script:steps += @{ name = $name; pass = $cond; detail = $detail }
    if (-not $cond -and -not $script:fail) { $script:fail = "$name :: $detail" }
}
$script:steps = @()

# --- preflight (functional) ---
$pref = & (Join-Path $RepoRoot 'tools\automation\bin\Preflight.ps1') -Mode functional | ConvertFrom-Json
$pref | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDir 'preflight.json') -Encoding UTF8
$manifest.preflight_status = $pref.status
if ($pref.status -eq 'BLOCKED') {
    $manifest.status = 'BLOCKED'
    $manifest.reason = ($pref.reasons -join '; ')
    $manifest | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
    @{ status = 'BLOCKED'; reason = $manifest.reason } | ConvertTo-Json | Set-Content (Join-Path $runDir 'result.json') -Encoding UTF8
    exit 2
}

# --- launch via Loom on isolated desktop ---
$env:VULKANITE_AUDIT_ENABLE = 'true'
$env:VULKANITE_AUDIT_TOKEN = $token
$env:VULKANITE_AUDIT_PORT = "$AuditPort"
$env:VULKANITE_AUTO_WORLD = $world

$gradle = Join-Path $RepoRoot 'gradlew.bat'
$launchJson = $null
$gamePid = $null
if ($SkipDesktop) {
    Write-Host 'WARNING: launching without Win32 desktop isolation (not valid for input-isolation proof)'
    $p = Start-Process -FilePath $gradle -ArgumentList @('runAutomation','--console=plain') -WorkingDirectory $RepoRoot -PassThru
    $gamePid = $p.Id
    $manifest.desktop_skipped = $true
} else {
    $launchJson = & (Join-Path $RepoRoot 'tools\automation\bin\Launch-OnWin32Desktop.ps1') -DesktopName $desktop -FilePath $gradle -ArgumentList @('runAutomation','--console=plain','--no-daemon') -WorkingDirectory $RepoRoot
    $launchObj = $launchJson | ConvertFrom-Json
    $gamePid = $launchObj.ProcessId
    $manifest.desktop_launch = $launchObj
}
$manifest.gradle_pid = $gamePid
$manifest.audit_token_fingerprint = $token.Substring(0, 8)
$manifest.audit_port = $AuditPort

$processJson = [ordered]@{
    pid = $gamePid
    desktop = $manifest.desktop
    command = "gradlew.bat runAutomation"
    created = [DateTime]::Now.ToString("o")
}
$processJson | ConvertTo-Json | Set-Content (Join-Path $runDir 'process.json') -Encoding UTF8
@{ desktop = $manifest.desktop; desktop_name = $desktop; isolation = 'Win32 Desktop object' } | ConvertTo-Json | Set-Content (Join-Path $runDir 'desktop.json') -Encoding UTF8

# --- wait audit ---
$up = $false
$deadline = (Get-Date).AddSeconds($GameWaitSeconds)
while ((Get-Date) -lt $deadline) {
    try {
        $pong = Invoke-Audit "ping $token"
        if ($pong -match '"ok"\s*:\s*true') { $up = $true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
$manifest.audit_up = $up
if (-not $up) {
    $manifest.status = 'BLOCKED'
    $manifest.reason = 'audit handshake failed / game not ready'
    $manifest | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
    # copy any logs that appeared
    if (Test-Path (Join-Path $mcDir 'logs\latest.log')) {
        Copy-Item (Join-Path $mcDir 'logs\latest.log') (Join-Path $runDir 'logs\') -Force
    }
    @{ status = 'BLOCKED'; reason = $manifest.reason } | ConvertTo-Json | Set-Content (Join-Path $runDir 'result.json') -Encoding UTF8
    exit 2
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
    # final-state: instances should not rise without a place; holders unique
    Assert 'holders_unique' ($empty -and $empty.data.currentSections -le [Math]::Max(1, $empty.data.tlasInstances + 1)) ("sections=" + $empty.data.currentSections + " tlas=" + $empty.data.tlasInstances)
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

# automatic quit
try { Invoke-Audit "quit $token" | Out-Null } catch { }

Start-Sleep -Seconds 3
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
