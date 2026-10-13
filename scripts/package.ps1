# 打包实验版
param([string] $OutputDirectory = (Join-Path $PSScriptRoot '../output/releases'), [switch] $SkipBuild)
. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$SkipBuild) {
    Resolve-SqlfinderJava | Out-Null
    Push-Location $root
    try { & mvn clean package; if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' } }
    finally { Pop-Location }
}
$jar = Resolve-SqlfinderJar $root
$output = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($output) | Out-Null
$name = 'SQLFINDER-0.1.0-experimental'
$zipPath = Join-Path $output ($name + '.zip')
if (Test-Path -LiteralPath $zipPath) { throw 'Release archive already exists; choose another output directory.' }
$stage = Join-Path $output ('.package-' + [Guid]::NewGuid().ToString('N'))
$release = Join-Path $stage $name
try {
    [IO.Directory]::CreateDirectory($release) | Out-Null
    $releaseJar = Join-Path $release 'sqlfinder-server-0.1.0.jar'
    Copy-Item -LiteralPath $jar -Destination $releaseJar
    $stream = [IO.File]::Open($releaseJar, 'Open', 'Read', 'Read')
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
    try {
        # Preserve Spring Boot offsets and stored nested Jars; reject stale builds instead of rewriting them.
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '(?i)(^|/)(auth|runtime|database)\.properties$|(^|/)application-local\.ya?ml$|\.mv\.db$|\.trace\.db$|\.lock\.db$') {
                throw 'Private configuration/data was found in the application Jar; run mvn clean package before packaging.'
            }
        }
        if (!$archive.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class')) { throw 'Not a runnable Spring Boot Jar.' }
    } finally { $archive.Dispose(); $stream.Dispose() }
    # Only public assets are copied. Never copy the actual config/data/output/source trees.
    Copy-Item -LiteralPath (Join-Path $root 'scripts') -Destination $release -Recurse
    [IO.Directory]::CreateDirectory((Join-Path $release 'config')) | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $root 'config-templates') -File) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $release ('config/' + $file.Name))
    }
    [IO.Directory]::CreateDirectory((Join-Path $release 'samples/sakila')) | Out-Null
    foreach ($sample in @('01-schema.sql', '02-data.sql', '03-reader-grants.sql', 'import.ps1', 'source.json',
            'queries.sql', 'expected-results.json', 'evaluation.json')) {
        Copy-Item -LiteralPath (Join-Path $root ('samples/sakila/' + $sample)) -Destination (Join-Path $release ('samples/sakila/' + $sample))
    }
    Copy-Item -LiteralPath (Join-Path $root 'README.md') -Destination $release
    $handbook = Join-Path $root '../docs/PROJECT-HANDBOOK.md'
    if (!(Test-Path -LiteralPath $handbook)) { throw 'Consolidated project handbook not found.' }
    [IO.Directory]::CreateDirectory((Join-Path $release 'docs')) | Out-Null
    Copy-Item -LiteralPath $handbook -Destination (Join-Path $release 'docs/PROJECT-HANDBOOK.md')
    [IO.Directory]::CreateDirectory((Join-Path $release 'licenses')) | Out-Null
    foreach ($notice in @('ECHARTS-LICENSE.txt', 'ECHARTS-NOTICE.txt')) {
        Copy-Item -LiteralPath (Join-Path $root ('src/main/resources/static/vendor/' + $notice)) -Destination (Join-Path $release ('licenses/' + $notice))
    }
    foreach ($license in @('LICENSE', 'LICENSE.md', 'LICENSE.txt')) {
        if (Test-Path -LiteralPath (Join-Path $root $license)) { Copy-Item -LiteralPath (Join-Path $root $license) -Destination $release }
    }
    $notices = @'
SQLFINDER experimental distribution, application version 0.1.0.
Apache ECharts license/notice are retained in licenses/.
Sakila source copyright and redistribution terms are retained at the top of samples/sakila/01-schema.sql and 02-data.sql.
Java dependency licenses/notices remain inside the nested dependency Jars.
No project source license is assigned by this packaging script; consult the repository's license if present.
'@
    [IO.File]::WriteAllText((Join-Path $release 'LICENSES.txt'), $notices, [Text.UTF8Encoding]::new($false))
    $quickstart = @'
SQLFINDER 0.1.0 实验版（Windows，Java 17 或以上）

1. 解压到可写目录。运行应用不需要 Maven 或 Node.js。
2. 将 JAVA_HOME 指向 Java 17 或以上的安装目录。
3. 在交互式 PowerShell 窗口运行 scripts/configure-login.ps1，按提示初始化登录账号，密码隐藏输入。
4. 将 config/application-local.example.yml 复制为 config/application-local.yml，使用环境变量或本机私密文件配置 MySQL 和 DeepSeek。
5. 使用 Sakila 时，先用本机 MySQL 管理员 login-path 运行 samples/sakila/import.ps1；然后参照 03-reader-grants.sql 为业务只读账号授予 SELECT。
6. 从 config/auth.properties.example 追加所需的 allowed-tables 表授权到自动生成的 config/auth.properties，保留原密码哈希。
7. 运行 scripts/start.ps1，打开 http://localhost:8081/ 登录；网页设置可继续配置模型与数据库。
8. 登录后的 GET /api/system/diagnostics 检查模型密钥是否配置、数据库只读权限与授权表、本地历史可读性，不调用真实模型。

备份和恢复前先停止应用。
备份：scripts/backup.ps1 -BackupPath D:/private/sqlfinder-backup.zip
恢复到没有旧配置和历史的安装目录：scripts/restore.ps1 -BackupPath D:/private/sqlfinder-backup.zip
备份包含私密凭据与查询结果，请妥善保管，不要上传；MySQL 业务数据需单独备份。
脚本只支持默认 data/history 路径；若使用自定义历史路径，停止应用后另行复制对应 H2 文件。
设计说明见 docs/PROJECT-HANDBOOK.md；固定问题和参考口径见 samples/sakila/evaluation.json。
'@
    [IO.File]::WriteAllText((Join-Path $release 'START-HERE.txt'), $quickstart, [Text.UTF8Encoding]::new($false))
    [IO.Compression.ZipFile]::CreateFromDirectory($stage, $zipPath, [IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Host "Experimental distribution created: $zipPath"
    Write-Host ('SHA256: ' + (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash)
} finally {
    # Verify the exact staging path remains inside the explicitly selected output directory.
    if ($stage.StartsWith($output.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $stage -Leaf).StartsWith('.package-') -and (Test-Path -LiteralPath $stage)) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
