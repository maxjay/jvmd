# Exercise the Windows-facing installer on Windows PowerShell 5.1 without modifying WSL.
$ErrorActionPreference = 'Stop'
$global:JvmdInstallerTestCalls = New-Object 'Collections.Generic.List[object]'
$global:JvmdInstallerWslAvailable = $true
function global:wsl.exe {
    $global:JvmdInstallerTestCalls.Add(@($args))
    $global:LASTEXITCODE = $(if ($global:JvmdInstallerWslAvailable) { 0 } else { 1 })
}
$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($testDirectory) | Out-Null
try {
    $installer = Join-Path $testDirectory 'install-wsl.ps1'
    $source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '../install-wsl.ps1'))
    # Windows source checkouts may convert LF to CRLF; the embedded Bash must remain LF.
    [IO.File]::WriteAllText($installer, $source.Replace("`r`n", "`n").Replace("`n", "`r`n"))
    & $installer -Version 'v0.1.0-preview.1' -Distribution Ubuntu
    if ($global:JvmdInstallerTestCalls.Count -ne 2) { throw 'Expected availability check followed by installation' }
    $invocation = $global:JvmdInstallerTestCalls[1]
    if ($invocation[0] -ne '--distribution' -or $invocation[1] -ne 'Ubuntu' -or $invocation[3] -ne 'bash') { throw 'Wrong WSL host or entry point' }
    if ($invocation[8] -ne 'v0.1.0-preview.1') { throw 'Version was not passed as an argument' }
    $decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($invocation[7]))
    if ($decoded.Contains("`r") -or -not $decoded.Contains('sha256sum -c install.sh.sha256')) { throw 'Invalid Linux script or missing checksum verification' }
    foreach ($invalid in @('v0.1.0; touch unwanted', '../../v0.1.0')) {
        $rejected = $false
        try { & $installer -Version $invalid } catch { $rejected = $true }
        if (-not $rejected -or $global:JvmdInstallerTestCalls.Count -ne 2) { throw 'Invalid version reached WSL' }
    }
    $global:JvmdInstallerWslAvailable = $false
    $rejected = $false
    try { & $installer -Version 'v0.1.0' } catch { $rejected = $true }
    if (-not $rejected -or $global:JvmdInstallerTestCalls.Count -ne 3) { throw 'Missing WSL was not reported before installation' }
    Write-Host 'Windows installer: argument forwarding, CRLF handling, checksum script, validation, and unavailable-WSL handling passed.'
} finally {
    Remove-Item Function:\wsl.exe
    Remove-Variable JvmdInstallerTestCalls,JvmdInstallerWslAvailable -Scope Global
    Remove-Item -Recurse -Force $testDirectory
}
# The unavailable-WSL case deliberately set a native failure code; do not leak the mock into the runner.
$global:LASTEXITCODE = 0
