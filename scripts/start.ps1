# 启动应用
param([int] $Port = 8081)
. (Join-Path $PSScriptRoot 'common.ps1')
if ($Port -lt 1 -or $Port -gt 65535) { throw 'Invalid port.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$java = Resolve-SqlfinderJava
$jar = Resolve-SqlfinderJar $root
if (!(Test-Path -LiteralPath (Join-Path $root 'config/auth.properties'))) {
    throw 'Login is not configured. Run scripts/configure-login.ps1 first.'
}
$listener = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
    Where-Object { $_.Port -eq $Port }
if ($listener) { throw "Port $Port is already in use. Stop the old app or choose another -Port." }
$lock = $null
Push-Location $root
try {
    $lock = [IO.File]::Open((Join-Path $root '.sqlfinder-running.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    Write-Host "Starting SQLFINDER at http://localhost:$Port/ (Ctrl+C to stop)."
    & $java -jar $jar "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw 'Application startup failed; review the terminal diagnostics.' }
} finally {
    if ($lock) { $lock.Dispose() }
    Pop-Location
}
