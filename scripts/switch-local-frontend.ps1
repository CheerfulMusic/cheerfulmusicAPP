#requires -Version 7
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('Main', 'Pinned')][string]$Mode)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $workspace '.cache/runtime'
$service = Join-Path $runtime 'production-main'
$stateFile = Join-Path $runtime 'local.json'
$baseConfig = Join-Path $service 'vite.production-main.config.mjs'
$devConfig = Join-Path $service 'vite.main-hmr.config.mjs'
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source

function Start-Web($root, $config) {
    $vite = Join-Path $root 'node_modules/vite/bin/vite.js'
    $child = Start-Process $node -ArgumentList @("`"$vite`"", '--config', "`"$config`"", '--configLoader', 'native', '--port', '5173', '--strictPort') -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $service 'web.out.log') -RedirectStandardError (Join-Path $service 'web.err.log')
    try {
        for ($attempt = 0; $attempt -lt 80; $attempt++) {
            $child.Refresh()
            if ($child.HasExited) { throw 'Vite 已退出，请查看 web.err.log。' }
            try { $ready = (Invoke-WebRequest 'http://127.0.0.1:5173/@vite/client' -TimeoutSec 1).StatusCode -eq 200 } catch { $ready = $false }
            if ($ready) {
                $web.id = $child.Id
                $web.started = $child.StartTime.ToUniversalTime().Ticks
                $state.frontendRoot = $root
                $temporary = "$stateFile.switch.tmp"
                [IO.File]::WriteAllText($temporary, ($state | ConvertTo-Json -Depth 8))
                [IO.File]::Move($temporary, $stateFile, $true)
                return
            }
            Start-Sleep -Milliseconds 250
        }
        throw '等待前端启动超时。'
    } catch {
        if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit() }
        throw
    }
}

$lock = [IO.File]::Open((Join-Path $runtime 'local.lifecycle.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$oldTarget = $env:VITE_API_PROXY_TARGET
$oldBase = $env:VITE_API_BASE_URL
try {
    $state = Get-Content -Raw $stateFile | ConvertFrom-Json
    $manifest = Get-Content -Raw (Join-Path $workspace '.secrets/production-main.manifest.json') | ConvertFrom-Json
    $web = $state.processes | Where-Object kind -eq web
    if ($state.mode -ne 'production-main' -or @($web).Count -ne 1) { throw '请先启动主库日常入口。' }
    $previous = Get-Process -Id $web.id
    $listener = Get-NetTCPConnection -LocalPort 5173 -LocalAddress '127.0.0.1' -State Listen
    if ($previous.StartTime.ToUniversalTime().Ticks -ne $web.started -or $listener.OwningProcess -ne $previous.Id) { throw '运行记录与 5173 进程不匹配。' }
    $previousRoot = $state.frontendRoot
    $previousConfig = if ($previousRoot -eq $manifest.frontendRoot) { $baseConfig } else { $devConfig }
    $root = $manifest.frontendRoot
    $config = $baseConfig
    if ($Mode -eq 'Main') {
        $worktrees = git -C $workspace -c core.quotepath=false worktree list --porcelain
        foreach ($line in $worktrees) {
            if ($line.StartsWith('worktree ')) { $candidate = $line.Substring(9) }
            if ($line -eq 'branch refs/heads/main') { $main = $candidate; break }
        }
        if (-not $main) { throw '找不到本地 main 的工作树。' }
        $root = (Resolve-Path (Join-Path $main 'Qingfeng_AI_Music_VUE')).Path
        $config = $devConfig
        # An existing local deletion omits this imported image. Read the committed
        # asset into Vite's cache without restoring over working-tree changes.
        $gift = 'src/assets/welcome/music-gift.png'
        $aliases = '[]'
        if (-not (Test-Path (Join-Path $root $gift))) {
            $cache = New-Item -ItemType Directory -Force (Join-Path $root 'node_modules/.cache/local-main')
            $image = Join-Path $cache.FullName 'music-gift.png'
            python -c "import subprocess,sys; from pathlib import Path; Path(sys.argv[2]).write_bytes(subprocess.check_output(['git','-C',sys.argv[1],'cat-file','blob',sys.argv[3]]))" $root $image "HEAD:Qingfeng_AI_Music_VUE/$gift"
            if ($LASTEXITCODE) { throw '无法读取 main 中的欢迎图片。' }
            $aliases = "[{ find: '../../../assets/welcome/music-gift.png', replacement: $(ConvertTo-Json $image -Compress) }]"
        }
        $rootJson = ConvertTo-Json $root -Compress
        [IO.File]::WriteAllText($config, @"
import base from './vite.production-main.config.mjs'
const root = $rootJson
export default { ...base, root, resolve: { ...base.resolve, alias: $aliases },
  server: { ...base.server, fs: { ...base.server.fs, allow: [root] } } }
"@)
    }
    if (-not (Test-Path (Join-Path $root 'node_modules/vite/bin/vite.js'))) { throw '所选前端尚未安装 Vite。' }
    if ($previousRoot -eq $root) { Write-Host "5173 已监听 $root"; return }
    $env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:8080'
    $env:VITE_API_BASE_URL = '/api'
    $previous.Kill()
    $previous.WaitForExit()
    try { Start-Web $root $config }
    catch { $failure = $_; Start-Web $previousRoot $previousConfig; throw $failure }
    Write-Host "5173 已监听 $root"
} finally {
    $env:VITE_API_PROXY_TARGET = $oldTarget
    $env:VITE_API_BASE_URL = $oldBase
    $lock.Dispose()
}
