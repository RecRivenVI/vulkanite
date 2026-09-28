# Invoke-DesktopCompatExperiment.ps1
# Phase A: verify Win32 desktop isolation can host a GUI/GL process chain.
# Does not use the user manual instance.
param(
    [string]$PrismRoot = 'E:\Minecraft\PrismLauncherDev',
    [string]$RunId = ('desktop-compat-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [switch]$LaunchPrism
)
$ErrorActionPreference = 'Stop'
$runDir = Join-Path $PrismRoot ".agent-runs\$RunId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$desktop = "VulkaniteAuto-$RunId"
$desktop = $desktop -replace '[^A-Za-z0-9\-]', ''

$lockDir = Join-Path $PrismRoot '.agent-locks'
New-Item -ItemType Directory -Force -Path $lockDir | Out-Null
$lock = Join-Path $lockDir 'Vulkanite-Auto.lock'
if (Test-Path $lock) {
    $prev = Get-Content $lock -Raw | ConvertFrom-Json
    if ($prev.agent -ne 'mimo-vulkanite-audit') { throw "Lock held by $($prev.agent) run=$($prev.run_id)" }
    Move-Item $lock (Join-Path $runDir "prior-lock-$(Get-Date -Format 'HHmmss').json") -Force
    Write-Host "Recovered own stale lock"
}
$fs = [System.IO.File]::Open($lock, 'CreateNew', 'Write')
$sw = New-Object System.IO.StreamWriter($fs)
$sw.Write((@{ agent='mimo-vulkanite-audit'; run_id=$RunId; instance='Vulkanite-Auto'; pid=$PID; acquired=(Get-Date).ToString('o') } | ConvertTo-Json))
$sw.Flush(); $sw.Dispose(); $fs.Dispose()

$result = [ordered]@{
    run_id = $RunId
    desktop = "WinSta0\$desktop"
    stage = 'started'
    launched = $false
    prisma_pid = $null
    error = $null
}

# 1) Smoke: launch cmd.exe on the desktop to prove CreateProcess+lpDesktop works.
try {
    $outTxt = Join-Path $runDir 'desktop-smoke.txt'
    $p = & (Join-Path $PSScriptRoot 'Launch-OnWin32Desktop.ps1') -DesktopName $desktop -FilePath 'C:\Windows\System32\cmd.exe' -ArgumentList @('/c', "echo vulkanite-auto-desktop-ok> $outTxt")
    $result.smoke = $p
    Start-Sleep -Seconds 2
    $result.smoke_file_exists = Test-Path (Join-Path $runDir 'desktop-smoke.txt')
} catch {
    $result.error = "smoke: $($_.Exception.Message)"
    $result.stage = 'smoke_failed'
}

# 2) Optional: launch Prism on the desktop (GL/OpenGL chain).
if ($LaunchPrism -and -not $result.error) {
    try {
        $prism = Join-Path $PrismRoot 'prismlauncher.exe'
        $launchArgs = @('--dir', $PrismRoot, '--launch', 'Vulkanite-Auto')
        $jp = & (Join-Path $PSScriptRoot 'Launch-OnWin32Desktop.ps1') -DesktopName $desktop -FilePath $prism -ArgumentList $launchArgs
        $result.launch = $jp
        $result.launched = $true
        $result.prisma_pid = ($jp | ConvertFrom-Json).ProcessId
        $result.stage = 'prism_launched_on_desktop'
    } catch {
        $result.error = "prism: $($_.Exception.Message)"
        $result.stage = 'prism_failed'
    }
}

$result | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDir 'desktop-experiment.json') -Encoding UTF8
Write-Host (Get-Content (Join-Path $runDir 'desktop-experiment.json') -Raw)
# lock left for the pilot to release
Write-Host "run_dir=$runDir"
Write-Host "desktop=WinSta0\$desktop"
