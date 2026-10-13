# 发布内容测试
param([Parameter(Mandatory)][string] $ZipPath, [Parameter(Mandatory)][string] $ReferenceJar)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$stream = [IO.File]::OpenRead([IO.Path]::GetFullPath($ZipPath))
$archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
$memory = [IO.MemoryStream]::new()
$checks = 0
function Assert-Content([bool] $Condition, [string] $Message) {
    if (!$Condition) { throw $Message }
    $script:checks++
}
try {
    $names = @($archive.Entries | ForEach-Object { $_.FullName })
    Assert-Content (@($names | Where-Object { $_ -match '(?i)references/|sqlbot/|(^|/)data/|(^|/)output/|(^|/)config/(auth|database|runtime)\.properties$|(^|/)application-local\.yml$' }).Count -eq 0) 'Private data or reference source included.'
    $jarEntry = @($archive.Entries | Where-Object { $_.Name -eq 'sqlfinder-server-0.1.0.jar' })
    Assert-Content ($jarEntry.Count -eq 1) 'Release must contain exactly one application Jar.'
    $inputStream = $jarEntry[0].Open()
    try { $inputStream.CopyTo($memory) } finally { $inputStream.Dispose() }
    $memory.Position = 0
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $packagedHash = ([BitConverter]::ToString($hasher.ComputeHash($memory))).Replace('-', '') }
    finally { $hasher.Dispose() }
    Assert-Content ($packagedHash -eq (Get-FileHash -LiteralPath $ReferenceJar -Algorithm SHA256).Hash) 'Packaging rewrote the Spring Boot Jar.'
    $memory.Position = 0
    $jar = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        Assert-Content ($null -eq $jar.GetEntry('BOOT-INF/classes/application-local.yml')) 'Private local YAML embedded.'
        Assert-Content ($null -ne $jar.GetEntry('BOOT-INF/classes/static/index.html')) 'Frontend missing.'
        Assert-Content ($null -ne $jar.GetEntry('BOOT-INF/classes/db/history/V3__workflow_runs.sql')) 'Workflow migration missing.'
    } finally { $jar.Dispose() }
    foreach ($suffix in @('/START-HERE.txt', '/scripts/start.cmd', '/scripts/backup.ps1', '/scripts/restore.ps1',
            '/config/application-local.example.yml', '/samples/sakila/evaluation.json', '/docs/PROJECT-HANDBOOK.md', '/licenses/ECHARTS-LICENSE.txt')) {
        Assert-Content (@($names | Where-Object { $_.EndsWith($suffix) }).Count -eq 1) ('Release asset missing: ' + $suffix)
    }
    Write-Output ('PACKAGE_CONTENT_VERIFIED checks=' + $checks)
} finally { $archive.Dispose(); $stream.Dispose(); $memory.Dispose() }
