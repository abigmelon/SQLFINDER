# 离线恢复
param([Parameter(Mandatory)][string] $BackupPath, [string] $Root = (Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression
$rootPath = [IO.Path]::GetFullPath($Root)
$sourcePath = [IO.Path]::GetFullPath($BackupPath)
if (!(Test-Path -LiteralPath $sourcePath -PathType Leaf)) { throw 'Backup archive not found.' }
Assert-DefaultHistoryPath $rootPath
[IO.Directory]::CreateDirectory($rootPath) | Out-Null
$handles = Get-OfflineFiles $rootPath
$archive = $null
$input = $null
$staged = @()
$published = @()
try {
    $input = [IO.File]::Open($sourcePath, 'Open', 'Read', 'Read')
    $archive = [IO.Compression.ZipArchive]::new($input, [IO.Compression.ZipArchiveMode]::Read, $true)
    if ($archive.Entries.Count -gt 7) { throw 'Invalid backup entry count.' }
    $manifestEntry = $archive.GetEntry('manifest.json')
    if (!$manifestEntry -or $manifestEntry.Length -gt 32768) { throw 'Backup manifest missing or too large.' }
    $reader = [IO.StreamReader]::new($manifestEntry.Open(), [Text.Encoding]::UTF8)
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($manifest.format -ne 'sqlfinder-offline-backup-v1' -or @($manifest.files).Count -eq 0) { throw 'Unsupported backup format.' }
    $allowed = Get-BackupEntryNames
    $seen = @{}
    foreach ($entry in $archive.Entries) {
        if ($seen.ContainsKey($entry.FullName)) { throw 'Duplicate backup entries are not allowed.' }
        $seen[$entry.FullName] = $true
        if ($entry.FullName -ne 'manifest.json' -and $entry.FullName -notin $allowed) { throw 'Unexpected backup entry; restore refused.' }
    }
    $seenFiles = @{}
    foreach ($file in @($manifest.files)) {
        if ($file.path -notin $allowed -or $seenFiles.ContainsKey($file.path) -or $file.sha256 -notmatch '^[a-f0-9]{64}$') {
            throw 'Invalid backup manifest entry.'
        }
        $seenFiles[$file.path] = $true
        $entry = $archive.GetEntry($file.path)
        if (!$entry -or $entry.Length -ne $file.bytes -or $entry.Length -gt 10737418240) { throw 'Backup size validation failed.' }
        $target = [IO.Path]::GetFullPath((Join-Path $rootPath $file.path))
        if (!$target.StartsWith($rootPath.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Restore target is outside the selected directory.'
        }
        if (Test-Path -LiteralPath $target) { throw 'Restore target exists; use an empty installation or move old files aside first.' }
        # Reject junctions/symlinks in destination ancestry before any file write.
        $ancestor = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($target))
        while ($ancestor) {
            if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Restore through a linked directory is not allowed.' }
            $ancestor = $ancestor.Parent
        }
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
        $temporary = Join-Path ([IO.Path]::GetDirectoryName($target)) ('.restore-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        $staged += @{ path = $temporary; target = $target }
        $stream = [IO.File]::Open($temporary, 'CreateNew', 'ReadWrite', 'None')
        $entryStream = $entry.Open()
        try {
            $entryStream.CopyTo($stream); $stream.Position = 0
            $hasher = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($hasher.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
            finally { $hasher.Dispose() }
            if ($hash -ne $file.sha256) { throw 'Backup checksum mismatch; restore refused.' }
        } finally { $entryStream.Dispose(); $stream.Dispose() }
    }
    if ($archive.Entries.Count -ne @($manifest.files).Count + 1) { throw 'Backup entries do not match the manifest.' }
    foreach ($item in $staged) { [IO.File]::Move($item.path, $item.target); $published += $item.target }
    Write-Host "Offline restore completed: $rootPath"
    Write-Host 'MySQL business data is external and must be restored separately. History is restored to data/history.mv.db.'
} catch {
    foreach ($path in $published) { if (Test-Path -LiteralPath $path) { [IO.File]::Delete($path) } }
    throw
} finally {
    foreach ($item in $staged) { if (Test-Path -LiteralPath $item.path) { [IO.File]::Delete($item.path) } }
    if ($archive) { $archive.Dispose() }
    if ($input) { $input.Dispose() }
    foreach ($handle in $handles.Values) { $handle.Dispose() }
}
