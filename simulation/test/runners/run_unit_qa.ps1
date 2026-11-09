$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
Set-Location $RepoRoot

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $StudioJbr = "C:\Program Files\Android\Android Studio1\jbr"
    if (Test-Path $StudioJbr) {
        $env:JAVA_HOME = $StudioJbr
    }
}
if (-not $env:ANDROID_HOME) {
    $DefaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $DefaultSdk) {
        $env:ANDROID_HOME = $DefaultSdk
    }
}
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

Write-Host "== [1/2] Running JVM Unit Tests & Android Lint ==" -ForegroundColor Cyan
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug

Write-Host "== [2/2] Running Declarative Scenario & Codebase Invariant QA ==" -ForegroundColor Cyan
python simulation/test/runners/run_scenario_qa.py

Write-Host "ALL QA CHECKS PASSED." -ForegroundColor Green
