param(
    [Parameter(Mandatory)][string]$NgxSdk,
    [Parameter(Mandatory)][string]$BuildDirectory,
    [Parameter(Mandatory)][string]$InstallDirectory,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$VulkanSdk = $env:VULKAN_SDK,
    [string]$Generator = 'Visual Studio 18 2026'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$sdk = (Resolve-Path -LiteralPath $NgxSdk).Path
foreach ($file in 'include/nvsdk_ngx_vk.h', 'lib/Windows_x86_64/rel/nvngx_dlss.dll', 'lib/Windows_x86_64/rel/nvngx_dlssd.dll') {
    if (-not (Test-Path -LiteralPath (Join-Path $sdk $file))) { throw "Missing SDK file: $file" }
}
$oldJava = $env:JAVA_HOME
$oldVulkan = $env:VULKAN_SDK
try {
    $env:JAVA_HOME = $JavaHome
    $env:VULKAN_SDK = $VulkanSdk
    & cmake -S (Join-Path $repo 'native') -B $BuildDirectory -G $Generator -A x64 "-DNGX_SDK=$sdk"
    if ($LASTEXITCODE -ne 0) { throw 'Native configuration failed' }
    & cmake --build $BuildDirectory --config Release
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
    New-Item -ItemType Directory -Force -Path $InstallDirectory | Out-Null
    Copy-Item -LiteralPath (Join-Path $BuildDirectory 'Release/vulkanite_dlss.dll') -Destination $InstallDirectory -Force
    foreach ($dll in 'nvngx_dlss.dll', 'nvngx_dlssd.dll') {
        Copy-Item -LiteralPath (Join-Path $sdk "lib/Windows_x86_64/rel/$dll") -Destination $InstallDirectory -Force
    }
    Get-ChildItem -LiteralPath $InstallDirectory -Filter '*.dll' | Get-FileHash -Algorithm SHA256
} finally {
    $env:JAVA_HOME = $oldJava
    $env:VULKAN_SDK = $oldVulkan
}
