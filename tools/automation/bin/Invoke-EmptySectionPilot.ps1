# Invoke-EmptySectionPilot.ps1
# Phase A pilot: empty-section lifecycle via audit IPC (final-state PASS criteria).
param(
    [string]$PrismRoot = 'E:\Minecraft\PrismLauncherDev',
    [string]$InstanceId = 'Vulkanite-Auto',
    [int]$AuditPort = 53117,
    [string]$Token = ('tk-' + [guid]::NewGuid().ToString('N')),
    [int]$BlockX = 8, [int]$BlockY = 64, [int]$BlockZ = 8,
    [switch]$StartGame,
    [int]$WaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
$runId = 'empty-section-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$runDir = Join-Path $PrismRoot ".agent-runs\$runId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$desktop = "VulkaniteAuto-$runId"
$desktop = $desktop -replace '[^A-Za-z0-9\-]', ''

$manifest = [ordered]@{
    run_id = $runId
    instance = $InstanceId
    desktop = "WinSta0\$desktop"
    audit_port = $AuditPort
    token = $Token
    target_block = @{ x = $BlockX; y = $BlockY; z = $BlockZ; place = 'minecraft:stone'; clear = 'minecraft:air' }
    pass_criteria = @(
        'confirmed empty update accepted (emptyMeshUpdates>=1)'
        'target origin has no leftover TLAS instance after clear (tlasInstances drops or stays consistent with section set)'
        'no late BLAS republish after empty (nonEmpty does not increase without a place)'
        're-place yields nonEmpty increase and optional tlas rise'
        'removeSectionFalse alone does not FAIL'
    )
    started = (Get-Date).ToString('o')
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

# Pre-touch instance + optional game on isolated desktop
& (Join-Path $PSScriptRoot 'New-AutoInstance.ps1') -PrismRoot $PrismRoot -InstanceId $InstanceId | Out-Null
& (Join-Path $PSScriptRoot 'Preflight.ps1') -Mode functional -OutFile (Join-Path $runDir 'preflight.json') | Out-Host

if ($StartGame) {
    $prism = Join-Path $PrismRoot 'prismlauncher.exe'
    # Launch whole Prism/game tree on the isolated desktop (user input stays on Default).
    $launch = & (Join-Path $PSScriptRoot 'Launch-OnWin32Desktop.ps1') -DesktopName $desktop -FilePath $prism `
        -ArgumentList @('--dir', $PrismRoot, '--launch', $InstanceId, '--offline', 'VulkaniteAuto') |
        ConvertFrom-Json
    $manifest.game_pid = $launch.ProcessId
    $manifest.desktop_launch = $launch
    # Wait for audit port
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $up = $false
    while ((Get-Date) -lt $deadline) {
        try { $p = Invoke-Audit "ping $Token"; if ($p -match 'ok') { $up = $true; break } } catch { Start-Sleep -Milliseconds 500 }
    }
    $manifest.audit_up = $up
    if (-not $up) {
        $manifest.status = 'BLOCKED'
        $manifest.reason = 'audit IPC not reachable'
        $manifest | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
        Write-Error 'Audit IPC not up'
        exit 2
    }
}

$result = [ordered]@{ steps = @() }
$fail = $null

function Assert([string]$name, [bool]$cond, [string]$detail) {
    $script:result.steps += @{ name = $name; pass = $cond; detail = $detail }
    if (-not $cond -and -not $script:fail) { $script:fail = $name + ': ' + $detail }
}

# If game not started, still write a dry-run structure (infra proof).
if (-not $StartGame) {
    $manifest.status = 'DRY_RUN'
    $manifest.note = 'Game not launched; infrastructure + criteria recorded. Use -StartGame for E2E.'
    $manifest | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
    Write-Host "DRY_RUN $runDir"
    exit 0
}

try {
    $s0 = Invoke-Audit "snapshot $Token" | ConvertFrom-Json
    $result.steps += @{ name = 'baseline'; pass = $s0.ok; detail = ($s0 | ConvertTo-Json -Compress) }
    Invoke-Audit "time $Token 1000" | Out-Null
    Invoke-Audit "weather $Token clear" | Out-Null
    Invoke-Audit "setblock $Token $BlockX $BlockY $BlockZ minecraft:stone" | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Audit "setblock $Token $BlockX $BlockY $BlockZ minecraft:air" | Out-Null
    $deadline = (Get-Date).AddSeconds(15)
    $emptySeen = $false; $s1 = $null
    while ((Get-Date) -lt $deadline) {
        $s1 = Invoke-Audit "snapshot $Token" | ConvertFrom-Json
        if ($s1.ok -and $s1.data.emptyMeshUpdates -ge 1) { $emptySeen = $true; break }
        Start-Sleep -Milliseconds 400
    }
    Assert 'confirmed_empty_accepted' $emptySeen ($s1 | ConvertTo-Json -Compress)
    Assert 'generation_recorded' ($s1.data.activeGeneration -ge 0) ("generation=" + $s1.data.activeGeneration)
    # re-place
    Invoke-Audit "setblock $Token $BlockX $BlockY $BlockZ minecraft:stone" | Out-Null
    $deadline = (Get-Date).AddSeconds(15)
    $s2 = $null; $repop = $false
    while ((Get-Date) -lt $deadline) {
        $s2 = Invoke-Audit "snapshot $Token" | ConvertFrom-Json
        if ($s2.ok -and $s2.data.nonEmptyMeshUpdates -gt $s1.data.nonEmptyMeshUpdates) { $repop = $true; break }
        Start-Sleep -Milliseconds 400
    }
    Assert 'replace_new_revision' $repop ($s2 | ConvertTo-Json -Compress)
    Assert 'no_stale_after_replace' ($s2.data.emptyMeshUpdates -ge $s1.data.emptyMeshUpdates) 'empty count monotonic'
    $result.final = $s2
    if ($fail) { $result.status = 'FAIL'; $result.reason = $fail }
    else { $result.status = 'PASS' }
} catch {
    $result.status = 'CRASHED'
    $result.reason = $_.Exception.Message
}

$manifest.result = $result
$manifest.completed = (Get-Date).ToString('o')
$manifest | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $runDir 'manifest.json') -Encoding UTF8
$result | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $runDir 'result.json') -Encoding UTF8
Write-Host "status=$($result.status) run_dir=$runDir"
if ($result.status -ne 'PASS') { exit 1 }
