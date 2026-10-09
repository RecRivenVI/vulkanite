# Preflight.ps1 — tiered. Functional: CPU/RAM/commit + instance conflict.
# Benchmark (not Phase A): also requires reliable GPU telemetry.
param(
    [ValidateSet('functional','benchmark')][string]$Mode = 'functional',
    [string]$OutFile = ''
)
$ErrorActionPreference = 'Stop'
$cutoff = (Get-Date).AddMinutes(-5)
$otherJava = Get-Process java, javaw -ErrorAction SilentlyContinue |
    Where-Object { $_.StartTime -gt $cutoff -or $true } |
    Select-Object Id, ProcessName, StartTime, Path
$cpu = $null
try {
    $cpu = (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 1).CounterSamples.CookedValue
} catch { $cpu = $null }
$os = Get-CimInstance Win32_OperatingSystem
$ramFreePct = [math]::Round(100.0 * $os.FreePhysicalMemory / $os.TotalVisibleMemorySize, 2)
$cs = Get-CimInstance Win32_ComputerSystem
$commitLimit = $cs.TotalVirtualMemorySize * 1KB
$commitFree = $os.FreeVirtualMemory * 1KB

$gpu = [ordered]@{ available = $false; detail = 'nvidia-smi failed or skipped' }
try {
    $smi = & nvidia-smi --query-gpu=name,driver_version,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw --format=csv,noheader 2>&1
    if ($LASTEXITCODE -eq 0 -and $smi) {
        $gpu = [ordered]@{ available = $true; raw = @($smi); source = 'nvidia-smi' }
    } else {
        $gpu.detail = "nvidia-smi exit=$LASTEXITCODE $smi"
    }
} catch {
    $gpu.detail = $_.Exception.Message
}

$blocked = $false
$reasons = @()
if ($Mode -eq 'benchmark' -and -not $gpu.available) {
    $blocked = $true
    $reasons += 'GPU telemetry unavailable for benchmark'
}
if ($ramFreePct -lt 8) { $blocked = $true; $reasons += "RAM free $ramFreePct% < 8%" }
if ($cpu -ne $null -and $cpu -gt 70) { $blocked = $true; $reasons += "CPU $cpu% > 70%" }

$out = [ordered]@{
    mode = $Mode
    timestamp = (Get-Date).ToString('o')
    cpu_percent = $cpu
    ram_free_percent = $ramFreePct
    commit_free = $commitFree
    commit_limit = $commitLimit
    gpu = $gpu
    other_java = @($otherJava)
    blocked = $blocked
    reasons = $reasons
    status = if ($blocked) { 'BLOCKED' } else { 'OK' }
}
$json = $out | ConvertTo-Json -Depth 6
if ($OutFile) { $json | Set-Content $OutFile -Encoding UTF8 }
$json
