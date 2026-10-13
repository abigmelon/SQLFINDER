# 脚本公共函数
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-SqlfinderJava {
    $candidate = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { $null }
    if (!$candidate -or !(Test-Path -LiteralPath $candidate -PathType Leaf)) {
        $command = Get-Command java -ErrorAction SilentlyContinue
        if (!$command) { throw 'Java 17 or newer is required. Configure JAVA_HOME first.' }
        $candidate = $command.Source
    }
    # --version writes stdout; Windows PowerShell treats -version stderr as an error.
    $version = (& $candidate --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '(?m)^(?:openjdk|java)\s+(?<major>\d+)' -or [int]$Matches.major -lt 17) {
        throw 'Java 17 or newer is required. Configure JAVA_HOME first.'
    }
    return $candidate
}

function Resolve-SqlfinderJar([string] $Root) {
    foreach ($relative in @('sqlfinder-server-0.1.0.jar', 'target/sqlfinder-server-0.1.0.jar')) {
        $candidate = Join-Path $Root $relative
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'Application Jar is missing. Run mvn clean package, or use the experimental distribution.'
}

function Assert-DefaultHistoryPath([string] $Root) {
    if ($env:SQLFINDER_HISTORY_PATH -and
        [IO.Path]::GetFullPath($env:SQLFINDER_HISTORY_PATH) -ne [IO.Path]::GetFullPath((Join-Path $Root 'data/history'))) {
        throw 'Custom SQLFINDER_HISTORY_PATH detected. Stop the app and back up/restore that external database separately.'
    }
}

function Get-OfflineFiles([string] $Root) {
    Assert-DefaultHistoryPath $Root
    # Hold exclusive Windows handles throughout the operation, including the H2 database itself.
    $handles = [Collections.Generic.Dictionary[string,IO.FileStream]]::new()
    try {
        $running = Join-Path $Root '.sqlfinder-running.lock'
        $handles.Add('.sqlfinder-running.lock', [IO.File]::Open($running, 'OpenOrCreate', 'ReadWrite', 'None'))
        foreach ($relative in @('config/auth.properties', 'config/runtime.properties', 'config/database.properties',
                'config/application-local.yml', 'src/main/resources/application-local.yml', 'data/history.mv.db')) {
            $path = Join-Path $Root $relative
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                $handles.Add($relative, [IO.File]::Open($path, 'Open', 'Read', 'None'))
            }
        }
        return ,$handles
    } catch {
        foreach ($handle in $handles.Values) { $handle.Dispose() }
        throw 'Offline operation refused: stop SQLFINDER (including IDE-launched instances) and release its files first.'
    }
}

function Get-BackupEntryNames {
    return @('config/auth.properties', 'config/runtime.properties', 'config/database.properties',
        'config/application-local.yml', 'src/main/resources/application-local.yml', 'data/history.mv.db')
}
