# New-AutoInstance.ps1 — clone a disposable automation instance (never touch user saves).
# Usage: .\New-AutoInstance.ps1 -InstanceId 'Vulkanite-Auto' [-SourceInstanceId 'Vulkanite 26.3-fabric']
param(
    [string]$PrismRoot = 'E:\Minecraft\PrismLauncherDev',
    [string]$SourceInstanceId = 'Vulkanite 26.3-fabric',
    [string]$InstanceId = 'Vulkanite-Auto'
)
$ErrorActionPreference = 'Stop'
$src = Join-Path $PrismRoot "instances\$SourceInstanceId"
$dst = Join-Path $PrismRoot "instances\$InstanceId"
if (-not (Test-Path $src)) { throw "Source instance missing: $src" }
if (Test-Path $dst) {
    Write-Host "Instance already exists: $dst (reuse)"
} else {
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    Copy-Item (Join-Path $src 'mmc-pack.json') (Join-Path $dst 'mmc-pack.json')
    Copy-Item (Join-Path $src 'instance.cfg') (Join-Path $dst 'instance.cfg')
    # rewrite instance name/uuid-ish fields conservatively
    $cfgPath = Join-Path $dst 'instance.cfg'
    $cfg = Get-Content $cfgPath
    $cfg = $cfg | ForEach-Object {
        if ($_ -like 'name=*') { "name=$InstanceId" } elseif ($_ -like 'uuid=*') { "uuid=auto-vulkanite-$InstanceId" } else { $_ }
    }
    Set-Content -Path $cfgPath -Value $cfg
    $mc = Join-Path $dst 'minecraft'
    New-Item -ItemType Directory -Force -Path $mc | Out-Null
    foreach ($d in @('mods','config','shaderpacks','saves','logs','crash-reports','options','resourcepacks')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $mc $d) | Out-Null
    }
}
$mc = Join-Path $dst 'minecraft'
# Deploy a known mod set (copy from source, skip disabled/backup)
$srcMods = Join-Path $src 'minecraft\mods'
$dstMods = Join-Path $mc 'mods'
Get-ChildItem $srcMods -Filter '*.jar' | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $dstMods $_.Name) -Force
}
# Fixed options contract for functional runs (not official perf until calibrated)
$options = @(
    'version:5023'
    'enableVsync:false'
    'fullscreen:false'
    'exclusiveFullscreen:false'
    'maxFps:260'
    'inactivityFpsLimit:"off"'
    'pauseOnLostFocus:false'
    'renderDistance:8'
    'simulationDistance:32'
    'graphicsPreset:"custom"'
    'preferredGraphicsBackend:"opengl"'
    'resourcePacks:["vanilla"]'
)
Set-Content -Path (Join-Path $mc 'options.txt') -Value $options -Encoding UTF8
# shaderpacks: copy Foundation zip only if present in source
$srcPack = Join-Path $src 'minecraft\shaderpacks\Vulkanite-Foundation.zip'
if (Test-Path $srcPack) {
    Copy-Item $srcPack (Join-Path $mc 'shaderpacks\Vulkanite-Foundation.zip') -Force
}
Write-Host "Auto instance ready: $dst"
Write-Host "mods:"
Get-ChildItem $dstMods -Filter '*.jar' | ForEach-Object { Write-Host "  $($_.Name)" }
