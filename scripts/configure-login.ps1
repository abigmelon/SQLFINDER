# 初始化登录
. (Join-Path $PSScriptRoot 'common.ps1')
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$java = Resolve-SqlfinderJava
$jar = Resolve-SqlfinderJar $root
Push-Location $root
try {
    & $java -jar $jar --configure-login
    if ($LASTEXITCODE -ne 0) { throw 'Login initialization failed.' }
} finally { Pop-Location }
