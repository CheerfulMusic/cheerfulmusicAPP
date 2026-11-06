[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw '请使用 PowerShell 7 运行此脚本。' }
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ((Split-Path $PSScriptRoot -Leaf) -ne 'scripts') { throw '请先将此脚本安装到项目 scripts 目录，再运行。' }
$runtimeBase = Join-Path $workspaceRoot '.cache\runtime'
$statePath = Join-Path $runtimeBase 'local.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '没有由项目启动入口记录的进程。'; return }
$lifecycleLock = $null
try {
    try { $lifecycleLock = [IO.File]::Open((Join-Path $runtimeBase 'local.lifecycle.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw '另一启动或停止流程正在操作，请稍后重试。' }
    if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '没有由项目启动入口记录的进程。'; return }
    $originalState = [IO.File]::ReadAllText($statePath)
    $state = $originalState | ConvertFrom-Json
    # Both legacy {id,started} entries and new {id,started,kind} entries use this one state file.
    $entries = @($state.processes); [Array]::Reverse($entries)
    $failed = $false; $stopped = 0
    foreach ($entry in $entries) {
        if (-not $entry.id -or -not $entry.started) { $failed = $true; continue }
        try {
            $running = Get-Process -Id ([int]$entry.id) -ErrorAction SilentlyContinue
            if ($running -and $running.StartTime.ToUniversalTime().Ticks -eq [long]$entry.started) {
                Stop-Process -Id ([int]$entry.id) -ErrorAction Stop
                $running.WaitForExit(3000) | Out-Null
                if (-not $running.HasExited) { $failed = $true } else { $stopped++ }
            }
        } catch { $failed = $true }
    }
    if ($failed) { throw '部分记录的进程未能停止；状态文件已保留。未按端口或名称终止其他程序。' }
    # Do not remove a new record if another, older entry point rewrote it while we were stopping.
    if ((Test-Path -LiteralPath $statePath) -and [IO.File]::ReadAllText($statePath) -ceq $originalState) { Remove-Item -LiteralPath $statePath }
    Write-Host "已停止本入口记录且 PID、启动时间均匹配的 $stopped 个进程。"
} finally { if ($lifecycleLock) { $lifecycleLock.Dispose() } }
