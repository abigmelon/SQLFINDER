# 离线备份
param([Parameter(Mandatory)][string] $BackupPath, [string] $Root = (Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression
$rootPath = [IO.Path]::GetFullPath($Root)
$destination = [IO.Path]::GetFullPath($BackupPath)
if (Test-Path -LiteralPath $destination) { throw 'Backup destination already exists; overwrite refused.' }
$handles = Get-OfflineFiles $rootPath
$archive = $null
$output = $null
$created = $false
try {
    if ($handles.Count -eq 0 -or ($handles.Count -eq 1 -and $handles.ContainsKey('.sqlfinder-running.lock'))) {
        throw 'No local configuration or history files were found.'
    }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    $output = [IO.File]::Open($destination, 'CreateNew', 'Write', 'None')
    $created = $true
    $archive = [IO.Compression.ZipArchive]::new($output, [IO.Compression.ZipArchiveMode]::Create, $true)
    $entries = @()
    foreach ($relative in (Get-BackupEntryNames)) {
        if (!$handles.ContainsKey($relative)) { continue }
        $source = $handles[$relative]
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $hash = ([BitConverter]::ToString($hasher.ComputeHash($source))).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose(); $source.Position = 0 }
        $entry = $archive.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
        $entryStream = $entry.Open()
        try { $source.CopyTo($entryStream) } finally { $entryStream.Dispose() }
        $entries += @{ path = $relative; sha256 = $hash; bytes = $source.Length }
    }
    $manifest = @{ format = 'sqlfinder-offline-backup-v1'; createdUtc = [DateTime]::UtcNow.ToString('o'); files = $entries }
    $manifestStream = $archive.CreateEntry('manifest.json').Open()
    $writer = [IO.StreamWriter]::new($manifestStream, [Text.UTF8Encoding]::new($false))
    try { $writer.Write(($manifest | ConvertTo-Json -Depth 5)) } finally { $writer.Dispose() }
    $archive.Dispose(); $archive = $null
    $output.Dispose(); $output = $null
    Write-Host "Offline backup created: $destination"
    Write-Host 'This archive contains private credentials and query results. Store it privately; do not include it in a release.'
} catch {
    if ($archive) { $archive.Dispose(); $archive = $null }
    if ($output) { $output.Dispose(); $output = $null }
    if ($created) { [IO.File]::Delete($destination) }
    throw
} finally {
    if ($archive) { $archive.Dispose() }
    if ($output) { $output.Dispose() }
    foreach ($handle in $handles.Values) { $handle.Dispose() }
}
