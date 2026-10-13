# Sakila样例导入
param(
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$LoginPath = 'sqlfinder-import',
    [string]$MysqlPath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $MysqlPath) { $MysqlPath = (Get-Command mysql -ErrorAction Stop).Source }
$MysqlPath = (Resolve-Path -LiteralPath $MysqlPath).Path
$sampleManifest = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'source.json') -Encoding UTF8 | ConvertFrom-Json

function Invoke-SampleSql([string]$Sql) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $MysqlPath
    $info.Arguments = "--login-path=$LoginPath --default-character-set=utf8mb4 --connect-timeout=5 --batch --skip-column-names --commands --skip-system-command"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardInputEncoding = New-Object System.Text.UTF8Encoding($false)
    $info.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $info.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($Sql)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $out = $stdout.GetAwaiter().GetResult()
    $err = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "MySQL import stopped: $err" }
    if ($err) { Write-Warning $err }
    return $out.Trim()
}

# Never overwrite an existing database, even when the previous import was incomplete.
$existing = Invoke-SampleSql "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='sqlfinder_sakila';"
if ($existing -ne '0') { throw 'sqlfinder_sakila already exists. Nothing was overwritten.' }
$schema = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '01-schema.sql') -Encoding UTF8
$data = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '02-data.sql') -Encoding UTF8
if ($schema -match '(?im)^\s*DROP\s+(DATABASE|SCHEMA)\b') { throw 'Schema deletion is prohibited.' }
Write-Host 'Creating the independent sqlfinder_sakila database...'
Invoke-SampleSql $schema | Out-Null
Write-Host 'Loading sample data...'
Invoke-SampleSql $data | Out-Null
foreach ($entry in $sampleManifest.expected_rows.PSObject.Properties) {
    $tableName = $entry.Name
    if ($tableName -notmatch '^[a-z_]+$') { throw 'Unexpected table identifier.' }
    $actual = Invoke-SampleSql "SELECT COUNT(*) FROM sqlfinder_sakila.$tableName;"
    if ([long]$actual -ne [long]$entry.Value) { throw "Row count mismatch: $tableName" }
    Write-Host "$tableName : $actual rows"
}
Write-Host 'Sakila import and all 16 row-count checks passed.' -ForegroundColor Green
