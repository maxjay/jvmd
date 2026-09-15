param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$')]
    [string]$Version,
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$Distribution = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw 'Install WSL 2 first with: wsl --install -d Ubuntu. Then restart Windows if requested.'
}
& wsl.exe --distribution $Distribution --exec true
if ($LASTEXITCODE -ne 0) {
    throw "WSL distribution '$Distribution' is unavailable. Run: wsl --install -d $Distribution"
}
# Execute the installer inside Linux: its CPU detection, paths, and runtime must all be Linux-side.
# Version is constrained above and passed as argv, never interpolated into shell source.
$script = @'
set -eu
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base="https://github.com/maxjay/jvmd/releases/download/$1"
curl -fsSL --retry 3 --proto '=https' --proto-redir '=https' "$base/install.sh" -o "$work/install.sh"
curl -fsSL --retry 3 --proto '=https' --proto-redir '=https' "$base/install.sh.sha256" -o "$work/install.sh.sha256"
(cd "$work" && sha256sum -c install.sh.sha256)
bash "$work/install.sh" "$1"
'@
$encodedScript = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))
# ASCII argv avoids Windows PowerShell 5.1's native quoting of multiline shell source.
& wsl.exe --distribution $Distribution --exec bash -c 'printf %s $1 | base64 -d | bash -s -- $2' installer $encodedScript $Version
if ($LASTEXITCODE -ne 0) { throw 'jvmd installation inside WSL failed.' }
Write-Host "Installed inside $Distribution. Run your OpenCode fork there, or open the project with VS Code's WSL extension."
