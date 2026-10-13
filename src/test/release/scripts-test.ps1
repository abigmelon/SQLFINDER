# 脚本行为测试
$scriptDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../scripts'))
. (Join-Path $scriptDirectory 'common.ps1')
Add-Type -AssemblyName System.IO.Compression
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('sqlfinder-script-test-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$checks = 0
function Assert-True([bool] $Value, [string] $Message) {
    if (!$Value) { throw $Message }
    $script:checks++
}
function Assert-Refused([scriptblock] $Action, [string] $Expected) {
    $caught = $null
    try { & $Action } catch { $caught = $_.Exception.Message }
    Assert-True ($null -ne $caught -and $caught -match $Expected) 'Expected safe refusal was not observed.'
}
try {
    Assert-True (Test-Path -LiteralPath (Resolve-SqlfinderJava) -PathType Leaf) 'Java runtime check failed.'
    $source = Join-Path $testRoot 'source'
    $restored = Join-Path $testRoot 'restored'
    [IO.Directory]::CreateDirectory((Join-Path $source 'config')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $source 'data')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $source 'config/auth.properties'), 'test-fixture-only')
    [IO.File]::WriteAllBytes((Join-Path $source 'data/history.mv.db'), [byte[]](0, 1, 2, 3, 255))
    $backup = Join-Path $testRoot 'backup.zip'
    & (Join-Path $scriptDirectory 'backup.ps1') -BackupPath $backup -Root $source
    & (Join-Path $scriptDirectory 'restore.ps1') -BackupPath $backup -Root $restored
    foreach ($file in @('config/auth.properties', 'data/history.mv.db')) {
        Assert-True ((Get-FileHash -LiteralPath (Join-Path $source $file)).Hash -eq
            (Get-FileHash -LiteralPath (Join-Path $restored $file)).Hash) 'Round-trip content mismatch.'
    }
    Assert-Refused { & (Join-Path $scriptDirectory 'backup.ps1') -BackupPath $backup -Root $source } 'already exists'
    Assert-Refused { & (Join-Path $scriptDirectory 'restore.ps1') -BackupPath $backup -Root $restored } 'target exists'
    $lock = [IO.File]::Open((Join-Path $source '.sqlfinder-running.lock'), 'Open', 'ReadWrite', 'None')
    try { Assert-Refused { & (Join-Path $scriptDirectory 'backup.ps1') -BackupPath (Join-Path $testRoot 'locked.zip') -Root $source } 'Offline operation refused' }
    finally { $lock.Dispose() }
    $historyLock = [IO.File]::Open((Join-Path $source 'data/history.mv.db'), 'Open', 'ReadWrite', 'None')
    try { Assert-Refused { & (Join-Path $scriptDirectory 'backup.ps1') -BackupPath (Join-Path $testRoot 'history-locked.zip') -Root $source } 'Offline operation refused' }
    finally { $historyLock.Dispose() }
    $restoredLock = [IO.File]::Open((Join-Path $restored '.sqlfinder-running.lock'), 'Open', 'ReadWrite', 'None')
    try { Assert-Refused { & (Join-Path $scriptDirectory 'restore.ps1') -BackupPath $backup -Root $restored } 'Offline operation refused' }
    finally { $restoredLock.Dispose() }

    $corrupt = Join-Path $testRoot 'corrupt.zip'
    Copy-Item -LiteralPath $backup -Destination $corrupt
    $stream = [IO.File]::Open($corrupt, 'Open', 'ReadWrite', 'None')
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $archive.GetEntry('manifest.json')
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        $manifest.files[0].sha256 = ('0' * 64)
        $entry.Delete()
        $writer = [IO.StreamWriter]::new($archive.CreateEntry('manifest.json').Open())
        try { $writer.Write(($manifest | ConvertTo-Json -Depth 5)) } finally { $writer.Dispose() }
    } finally { $archive.Dispose(); $stream.Dispose() }
    $corruptDestination = Join-Path $testRoot 'corrupt-restored'
    Assert-Refused { & (Join-Path $scriptDirectory 'restore.ps1') -BackupPath $corrupt -Root $corruptDestination } 'checksum mismatch'
    Assert-True (!(Test-Path -LiteralPath (Join-Path $corruptDestination 'config/auth.properties'))) 'Corrupted content was published.'
    Assert-True (@(Get-ChildItem -LiteralPath $corruptDestination -File -Recurse | Where-Object { $_.Name -like '.restore-*' }).Count -eq 0) 'Temporary restore files remain.'

    $malicious = Join-Path $testRoot 'malicious.zip'
    Copy-Item -LiteralPath $backup -Destination $malicious
    $stream = [IO.File]::Open($malicious, 'Open', 'ReadWrite', 'None')
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update)
    try { $archive.CreateEntry('../outside.txt') | Out-Null } finally { $archive.Dispose(); $stream.Dispose() }
    Assert-Refused { & (Join-Path $scriptDirectory 'restore.ps1') -BackupPath $malicious -Root (Join-Path $testRoot 'malicious-restored') } 'Unexpected backup entry'
    Assert-True (!(Test-Path -LiteralPath (Join-Path $testRoot 'outside.txt'))) 'Zip path traversal escaped the target.'
    Write-Host "SCRIPT_CHECKS_PASSED=$checks"
} finally {
    $absoluteTestRoot = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if ($absoluteTestRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $absoluteTestRoot -Leaf).StartsWith('sqlfinder-script-test-')) {
        Remove-Item -LiteralPath $absoluteTestRoot -Recurse -Force
    }
}
