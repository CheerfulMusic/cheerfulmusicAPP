$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location (Join-Path $workspaceRoot 'Qingfeng_AI_Music_VUE')
try {
    if (-not (Test-Path node_modules)) { & npm ci; if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' } }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
    & npm test
    if ($LASTEXITCODE -ne 0) { throw '前端测试失败。' }
} finally { Pop-Location }
# 只停止启停脚本记录的本项目进程，避免Windows锁住旧构建；不按端口终止其他程序。
& (Join-Path $PSScriptRoot 'stop-local.ps1')
& (Join-Path $workspaceRoot 'Qingfeng_AI_Music\scripts\build.ps1')
Write-Host '本地交付包已生成，包含前端页面与后端。运行 scripts/start-local.ps1 即可体验。'
