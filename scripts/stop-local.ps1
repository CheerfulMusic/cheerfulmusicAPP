$ErrorActionPreference='Stop'
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$statePath=Join-Path $workspaceRoot '.cache\runtime\local.json'
if(-not(Test-Path -LiteralPath $statePath)){Write-Host '没有由启动脚本记录的项目进程。';return}
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
foreach($entry in $state.processes){$running=Get-Process -Id $entry.id -ErrorAction SilentlyContinue;if($running -and $running.StartTime.ToUniversalTime().Ticks -eq $entry.started){Stop-Process -Id $entry.id;Write-Host '已停止本项目记录的进程。'}}
# 仅删除当前工作区内固定的进程记录，不按端口终止其他应用。
Remove-Item -LiteralPath $statePath
