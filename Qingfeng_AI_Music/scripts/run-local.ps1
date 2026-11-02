$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverJar = Join-Path $projectRoot 'qf-server\target\qf-server-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $serverJar)) { throw '请先运行 .\scripts\build.ps1。' }
& java '-jar' $serverJar '--spring.profiles.active=local'
if ($LASTEXITCODE -ne 0) { throw '本地服务启动失败。' }
