# Launch-OnWin32Desktop.ps1
# Creates WinSta0\<DesktopName> and launches a process on that desktop (not Default).
# Functional isolation: interactive user input on Default cannot reach this desktop.
param(
    [Parameter(Mandatory=$true)][string]$DesktopName,
    [Parameter(Mandatory=$true)][string]$FilePath,
    [string[]]$ArgumentList = @(),
    [string]$WorkingDirectory = ''
)
$ErrorActionPreference = 'Stop'
$src = @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public class Win32Desktop {
  [DllImport("user32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
  public static extern IntPtr CreateDesktop(string lpszDesktop, IntPtr lpszDevice, IntPtr pDevmode, int dwFlags, uint dwDesiredAccess, IntPtr lpsa);
  [DllImport("user32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
  public static extern IntPtr OpenDesktop(string lpszDesktop, int dwFlags, bool fInherit, uint dwDesiredAccess);
  [DllImport("user32.dll", SetLastError=true)]
  public static extern bool CloseDesktop(IntPtr hDesktop);
  [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
  public static extern bool CreateProcess(string lpApplicationName, StringBuilder lpCommandLine, IntPtr lpProcessAttributes, IntPtr lpThreadAttributes, bool bInheritHandles, uint dwCreationFlags, IntPtr lpEnvironment, string lpCurrentDirectory, ref STARTUPINFO lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);
  [DllImport("kernel32.dll")]
  public static extern bool CloseHandle(IntPtr hObject);
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  public struct STARTUPINFO {
    public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
    public int dwX; public int dwY; public int dwXSize; public int dwYSize; public int dwXCountChars; public int dwYCountChars;
    public int dwFillAttribute; public int dwFlags; public short wShowWindow; public short cbReserved2; public IntPtr lpReserved2;
    public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError;
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct PROCESS_INFORMATION { public IntPtr hProcess; public IntPtr hThread; public int dwProcessId; public int dwThreadId; }
}
'@
Add-Type -TypeDefinition $src -ErrorAction Stop

$access = 0x0100 # DESKTOP_CREATEWINDOW
$hDesk = [Win32Desktop]::OpenDesktop($DesktopName, 0, $false, 0x0001) # GENERIC_READ
if ($hDesk -eq [IntPtr]::Zero) {
    $hDesk = [Win32Desktop]::CreateDesktop($DesktopName, [IntPtr]::Zero, [IntPtr]::Zero, 0, 0x01FF, [IntPtr]::Zero)
    if ($hDesk -eq [IntPtr]::Zero) {
        throw "CreateDesktop failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    Write-Host "Created desktop WinSta0\$DesktopName"
} else {
    Write-Host "Opened existing desktop WinSta0\$DesktopName"
}

$si = New-Object Win32Desktop+STARTUPINFO
$si.cb = [Runtime.InteropServices.Marshal]::SizeOf($si)
$si.lpDesktop = "WinSta0\$DesktopName"
$si.dwFlags = 0x00000100 # STARTF_USESHOWWINDOW
$si.wShowWindow = 1
$pi = New-Object Win32Desktop+PROCESS_INFORMATION

# Prefer lpApplicationName + command line starting with quoted exe (CreateProcess contract).
function Quote-Arg([string]$a) {
    if ($a -eq $null) { return '""' }
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}
$exe = (Resolve-Path -LiteralPath $FilePath).Path
$cmd = (Quote-Arg $exe)
if ($ArgumentList.Count -gt 0) {
    $cmd = $cmd + ' ' + (($ArgumentList | ForEach-Object { Quote-Arg $_ }) -join ' ')
}
$cmdBuilder = New-Object System.Text.StringBuilder $cmd
$flags = 0x00000010 # CREATE_NEW_CONSOLE
$work = if ($WorkingDirectory) { $WorkingDirectory } else { (Split-Path $exe -Parent) }
$ok = [Win32Desktop]::CreateProcess($exe, $cmdBuilder, [IntPtr]::Zero, [IntPtr]::Zero, $false, $flags, [IntPtr]::Zero, $work, [ref]$si, [ref]$pi)
if (-not $ok) {
    $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
    throw "CreateProcess on desktop failed: $err cmd=$cmd exe=$exe"
}
Write-Host "Started PID=$($pi.dwProcessId) on WinSta0\$DesktopName"
Write-Host "CommandLine=$cmd"
[Win32Desktop]::CloseHandle($pi.hThread) | Out-Null
# Do not close hProcess — caller may wait.
Write-Output ([pscustomobject]@{
    Desktop = "WinSta0\$DesktopName"
    ProcessId = $pi.dwProcessId
    CommandLine = $cmd
} | ConvertTo-Json)
[Win32Desktop]::CloseDesktop($hDesk) | Out-Null
