[CmdletBinding()]
param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$python = (Get-Command python -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$git = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$common = (& $git -C $workspaceRoot rev-parse --path-format=absolute --git-common-dir).Trim()
if ($LASTEXITCODE -ne 0) { throw '无法确定仓库缓存范围，未构建。' }
$cacheRoot = Join-Path (Split-Path $common -Parent) '.cache'
# No stop/start, SSH, deployment or private configuration import in the build path.
& $python (Join-Path $PSScriptRoot 'release_gate.py') build --workspace $workspaceRoot --baseline (Join-Path $workspaceRoot 'Qingfeng_AI_Music/deploy/production-baseline.json') --java-home $JavaHome --cache-root $cacheRoot
if ($LASTEXITCODE -ne 0) { throw '可信构建被阻断；保留修改及失败证据，不停止任何运行进程。' }
Write-Host '本地构建完成；正式发布仍须独立的数据库、业务、安全验收和审批。'
