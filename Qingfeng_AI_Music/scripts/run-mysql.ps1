$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverJar = Join-Path $projectRoot 'qf-server\target\qf-server-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $serverJar)) { throw '请先运行 .\scripts\build.ps1。' }
$privateConfig = Join-Path $projectRoot 'config\local.properties'
$hasConfiguredUser = $false
if (Test-Path -LiteralPath $privateConfig) {
    $hasConfiguredUser = @(Get-Content -LiteralPath $privateConfig | Where-Object { $_ -match '^\s*spring\.datasource\.username\s*=\s*\S+' }).Count -gt 0
}
if (-not $hasConfiguredUser -and [string]::IsNullOrWhiteSpace($env:MYSQL_USERNAME)) {
    throw '请在 config\local.properties 中填写并保存实际数据库用户名，或设置 MYSQL_USERNAME 环境变量。'
}
Push-Location $projectRoot
try {
    & java '-jar' $serverJar '--spring.profiles.active=mysql'
    if ($LASTEXITCODE -ne 0) { throw 'MySQL开发服务启动失败，请检查私有配置。' }
} finally { Pop-Location }
