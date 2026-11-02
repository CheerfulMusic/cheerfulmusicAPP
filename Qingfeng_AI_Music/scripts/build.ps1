$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskRepository = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\.cache\maven'))
Push-Location $projectRoot
try {
    & mvn '-B' '-ntp' "-Dmaven.repo.local=$taskRepository" 'clean' 'verify'
    if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败。' }
} finally { Pop-Location }
