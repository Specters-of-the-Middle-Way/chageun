param(
    [string]$SdkPath = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$PythonPath = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe",
    [ValidateRange(5554, 5682)][int]$Port = 5554
)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $SdkPath 'platform-tools\adb.exe'
$taskEmulator = Join-Path $SdkPath 'emulator\emulator.exe'
if ($Port % 2) { throw 'Use an even emulator console port.' }
$taskSerial = "emulator-$Port"
foreach ($taskTool in @($taskAdb, $taskEmulator, $PythonPath)) {
    if (-not (Test-Path -LiteralPath $taskTool)) { throw "Missing tool: $taskTool" }
}
New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot '.tooling') | Out-Null
$taskRunning = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -like 'qemu-system-*' -and $_.CommandLine -match '-avd\s+DriveStep_Pleos(?:\s|$)'
})
if ($taskRunning.Count -gt 0) {
    if ($taskRunning.Count -ne 1 -or $taskRunning[0].CommandLine -notmatch '-no-passive-gps(?:\s|$)' -or
        $taskRunning[0].CommandLine -notmatch '-allow-host-audio(?:\s|$)' -or
        $taskRunning[0].CommandLine -notmatch "-port\s+$Port(?:\s|$)") {
        throw 'Close the existing DriveStep_Pleos emulator normally, then run this script again. Do not wipe data.'
    }
} else {
    if (@(& $taskAdb devices) -match "^$taskSerial\s") { throw "Port $Port belongs to another emulator." }
    Start-Process -FilePath $taskEmulator -ArgumentList @('-avd', 'DriveStep_Pleos', '-port', "$Port",
        '-no-snapshot', '-memory', '4096', '-gpu', 'host', '-no-metrics', '-crash-report-mode', 'never',
        '-camera-back', 'none', '-no-passive-gps', '-allow-host-audio') -WindowStyle Normal | Out-Null
}
$taskDeadline = (Get-Date).AddMinutes(4)
do {
    $taskReady = @(& $taskAdb -s $taskSerial shell getprop sys.boot_completed 2>$null) -contains '1'
    if (-not $taskReady) { Start-Sleep -Seconds 2 }
} while (-not $taskReady -and (Get-Date) -lt $taskDeadline)
if (-not $taskReady) { throw 'Emulator boot timed out.' }
$taskFeed = Join-Path $PSScriptRoot 'pleos-gps-feed.py'
$taskArguments = @('-X', 'utf8', ('"' + $taskFeed + '"'), '--adb', ('"' + $taskAdb + '"'), '--serial', $taskSerial)
Start-Process -FilePath $PythonPath -ArgumentList $taskArguments -WindowStyle Hidden | Out-Null
$taskStatus = Join-Path $taskRoot ".tooling\gps-feed-$taskSerial.json"
$taskFeedReady = $false
$taskFeedDeadline = (Get-Date).AddSeconds(15)
do {
    try {
        $taskState = Get-Content -LiteralPath $taskStatus -Raw | ConvertFrom-Json
        $taskFeedReady = ([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($taskState.last_sent_utc)).TotalSeconds -lt 5
    } catch { $taskFeedReady = $false }
    if (-not $taskFeedReady) { Start-Sleep -Milliseconds 500 }
} while (-not $taskFeedReady -and (Get-Date) -lt $taskFeedDeadline)
if (-not $taskFeedReady) { throw 'GPS feed did not become ready. Run scripts/pleos-gps-feed.py in a terminal to inspect the error.' }
Write-Host 'DriveStep_Pleos is running with corrected stationary test GPS at Bokjeong-dong.'
Write-Host 'This does not repair the native navigation empty-map style or fixed-position branch.'
