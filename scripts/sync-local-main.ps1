#requires -Version 7
[CmdletBinding(DefaultParameterSetName='Attach')]
param(
    [Parameter(ParameterSetName='Attach')][switch]$Attach,
    [Parameter(Mandatory,ParameterSetName='Apply')][switch]$Apply
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalMainServerArguments.ps1')
$root = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $root '.cache/runtime'
$service = Join-Path $runtime 'production-main'
$stateFile = Join-Path $runtime 'local.json'
$config = Get-Content -LiteralPath (Join-Path $root '.secrets/local-hot-update.json') -Raw | ConvertFrom-Json
$cache = [IO.Path]::GetFullPath($config.cacheRoot)
$manifest = Get-Content -LiteralPath (Join-Path $root '.secrets/production-main.manifest.json') -Raw | ConvertFrom-Json
if ($manifest.localDevelopmentFeatures -ne $true -or $cache -notmatch '(?i)^D:[\\/]') { throw '实时更新只适用于已启用的本机开发实例。' }

function Save-LocalState {
    $tmp = "$stateFile.$PID.tmp"
    [IO.File]::WriteAllText($tmp, ($state | ConvertTo-Json -Depth 12))
    [IO.File]::Move($tmp, $stateFile, $true)
}
function Get-RecordedProcess($kind, [int]$port = 0) {
    $entries = @($state.processes | Where-Object kind -eq $kind)
    if ($entries.Count -ne 1) { throw "无法确认本机 $kind 进程。" }
    $p = Get-Process -Id $entries[0].id -ErrorAction Stop
    if ($p.StartTime.ToUniversalTime().Ticks -ne [long]$entries[0].started) { throw "本机 $kind 进程已经变更。" }
    if ($port) {
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
        if (@($listener).Count -ne 1 -or $listener.LocalAddress -ne '127.0.0.1' -or $listener.OwningProcess -ne $p.Id) {
            throw "端口 $port 的监听与本机运行记录不符。"
        }
    }
    return $p
}
function Record-Child($kind, $child) {
    $state.processes = @($state.processes | Where-Object kind -ne $kind) + @(@{
        kind = $kind; id = $child.Id; started = $child.StartTime.ToUniversalTime().Ticks
    })
    Save-LocalState
}
function Start-Child($exe, $arguments, $directory, $kind) {
    $escaped = @($arguments | ForEach-Object {
        '"' + ([regex]::Replace(([regex]::Replace([string]$_, '(\\*)"', '$1$1\"')), '(\\+)$', '$1$1')) + '"'
    })
    $saved = @{}
    try {
        if ($kind -eq 'server') {
            foreach ($item in Get-ChildItem Env: | Where-Object { $_.Name -match '^(SPRING[_.]|MYSQL_|QINGFENG[_.])|^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$' }) {
                $saved[$item.Name] = $item.Value
                [Environment]::SetEnvironmentVariable($item.Name, $null, 'Process')
            }
        }
        return Start-Process -FilePath $exe -ArgumentList ($escaped -join ' ') -WorkingDirectory $directory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $cache "$kind.out.log") -RedirectStandardError (Join-Path $cache "$kind.err.log")
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
    }
}
function Stop-Owned($child) {
    if (!$child.HasExited) {
        $child.Kill()
        if (!$child.WaitForExit(10000)) { throw '本机进程未退出。' }
    }
}
function Start-Serving($kind, $exe, $arguments, $directory, $port, $url) {
    $child = Start-Child $exe $arguments $directory $kind
    try {
        Record-Child $kind $child
        $until = [DateTime]::UtcNow.AddSeconds(85)
        do {
            $child.Refresh()
            if ($child.HasExited) { throw "$kind 启动失败。详见 $cache/$kind.err.log 和 $kind.out.log。" }
            try {
                if ($kind -eq 'server') { $up = (Invoke-RestMethod $url -TimeoutSec 2).status -eq 'UP' }
                else { $up = (Invoke-WebRequest $url -TimeoutSec 2).StatusCode -eq 200 }
            } catch { $up = $false }
            if ($up) { [void](Get-RecordedProcess $kind $port); return $child }
            Start-Sleep -Milliseconds 350
        } while ([DateTime]::UtcNow -lt $until)
        throw "等待 $kind 就绪超时。"
    } catch { Stop-Owned $child; throw }
}
function Start-Server($jar, [bool]$development) {
    $uri = ([uri](Join-Path $root '.secrets/production-main.local.properties')).AbsoluteUri
    $arguments = Get-LocalMainServerArguments $jar $uri -Development:$development
    return Start-Serving 'server' (Join-Path $config.javaHome 'bin/java.exe') $arguments $service 8080 'http://127.0.0.1:8080/actuator/health'
}
function Start-Web($webRoot, $viteConfig, $node) {
    $env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:8080'
    $env:VITE_API_BASE_URL = '/api'
    $arguments = @((Join-Path $webRoot 'node_modules/vite/bin/vite.js'), '--config', $viteConfig, '--configLoader', 'native',
                   '--host', '127.0.0.1', '--port', '5173', '--strictPort')
    return Start-Serving 'web' $node $arguments $webRoot 5173 'http://127.0.0.1:5173/@vite/client'
}
function Set-WebCommit($webRoot, $commit) {
    $checkout = Split-Path $webRoot -Parent
    $changes = @(git -C $checkout status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0 -or $changes.Count -or (git -C $checkout branch --show-current)) { throw '实时前端工作树存在本地修改。' }
    git -C $checkout checkout --quiet --detach $commit
    if ($LASTEXITCODE -ne 0 -or (git -C $checkout rev-parse HEAD) -ne $commit) { throw '实时前端更新失败。' }
}
function New-WebConfig($webRoot) {
    $name = Split-Path (Split-Path $webRoot -Parent) -Leaf
    $dest = Join-Path $cache "$name.vite.mjs"
    $baseUrl = ([uri](Join-Path $service 'vite.production-main.config.mjs')).AbsoluteUri | ConvertTo-Json -Compress
    $webJson = ConvertTo-Json $webRoot -Compress
    $contents = @"
import base from $baseUrl
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
const root = $webJson
const require = createRequire(pathToFileURL(root + '/package.json'))
const vue = (await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)).default
export default { ...base, root, plugins: [vue(), ...base.plugins.filter(p => p.name !== 'vite:vue')],
  server: { ...base.server, fs: { ...base.server.fs, allow: [root] } } }
"@
    [IO.File]::WriteAllText($dest, $contents)
    return $dest
}

$lock = [IO.File]::Open((Join-Path $runtime 'local.lifecycle.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json -AsHashtable
    if ($state.mode -ne 'production-main' -or !$state.mainDatabase) { throw '需要已经启动的日常本机实例。' }
    $server = Get-RecordedProcess 'server' 8080
    $web = Get-RecordedProcess 'web' 5173
    [void](Get-RecordedProcess 'ssh' 13306)
    if (!$Apply) {
        if ($state.processes | Where-Object kind -eq 'hot-update') {
            try { [void](Get-RecordedProcess 'hot-update'); Write-Output '本机实时更新正在运行。'; return } catch {}
        }
        $python = (Get-Command python -CommandType Application | Select-Object -First 1).Source
        $child = Start-Child $python @('-B', '-u', (Join-Path $PSScriptRoot 'local_hot_update.py'), 'watch', $root) $service 'hot-update'
        try { Record-Child 'hot-update' $child } catch { Stop-Owned $child; throw }
        Write-Output '已启动本地 main 更新监听。'
        return
    }
    $plan = Get-Content -LiteralPath (Join-Path $cache 'plan.json') -Raw | ConvertFrom-Json
    if ($plan.runId -ne $state.runId -or $plan.commit -ne (git -C $root rev-parse refs/heads/main) -or
        $plan.activeFrontend -ne [IO.Path]::GetFullPath($state.frontendRoot) -or
        $plan.baselineSha256 -ne $manifest.jarSha256 -or $plan.previousBackend -ne $state.sourceCommit) { throw '构建时运行状态或 main 已改变，重新准备。' }
    $originalMetadata = @{}
    foreach ($key in 'sourceCommit','jarSha256','localJar','frontendRoot','hotWebConfig','hotMainCommit') {
        if ($state.Contains($key)) { $originalMetadata[$key] = $state[$key] }
    }
    $oldRoot = $state.frontendRoot
    $oldJar = if ($state.localJar) { $state.localJar } else { Join-Path $service 'app.jar' }
    $oldConfig = if ($state.hotWebConfig) { $state.hotWebConfig } elseif ($oldRoot -eq $manifest.frontendRoot) {
        Join-Path $service 'vite.production-main.config.mjs'
    } else { Join-Path $service 'vite.main-hmr.config.mjs' }
    $oldNode = $web.Path
    $choices = @('slot-a','slot-b') | ForEach-Object { [IO.Path]::GetFullPath((Join-Path $cache "$_/Qingfeng_AI_Music_VUE")) }
    if ($plan.newFrontend) {
        if ($plan.frontendRoot -notin $choices) { throw '前端路径不属于本机更新目录。' }
        if ($plan.hmr) {
            if ([IO.Path]::GetFullPath($oldRoot) -ne $plan.frontendRoot -or (git -C $oldRoot rev-parse HEAD) -ne $plan.previousWebCommit) { throw '实时前端来源已改变。' }
        } else {
            if ((git -C $plan.frontendRoot rev-parse HEAD) -ne $plan.commit) { throw '备用前端源码版本已变更。' }
            $viteConfig = New-WebConfig $plan.frontendRoot
        }
    }
    if ($plan.newBackend) {
        $jarChoices = @('a.jar','b.jar') | ForEach-Object { [IO.Path]::GetFullPath((Join-Path $cache "jars/$_")) }
        if ($plan.jar -notin $jarChoices -or $plan.jar -eq $oldJar -or
            (Get-FileHash -LiteralPath $plan.jar).Hash -ine $plan.jarSha256) { throw '备用后端 JAR 路径或哈希异常。' }
    }
    $apiStopped = $false; $webStopped = $false; $hmrSwitched = $false
    $newServer = $null; $newWeb = $null
    try {
        if ($plan.newBackend) {
            Stop-Owned $server; $apiStopped = $true
            $newServer = Start-Server $plan.jar $true
        }
        if ($plan.newFrontend -and $plan.hmr) {
            Set-WebCommit $plan.frontendRoot $plan.commit
            $hmrSwitched = $true
            if ((Invoke-WebRequest 'http://127.0.0.1:5173/@vite/client' -TimeoutSec 3).StatusCode -ne 200) { throw '前端 HMR 服务不可用。' }
        } elseif ($plan.newFrontend) {
            Stop-Owned $web; $webStopped = $true
            $newWeb = Start-Web $plan.frontendRoot $viteConfig $plan.node
        }
        if ($plan.newBackend) {
            $state.localJar = $plan.jar
            $state.jarSha256 = $plan.jarSha256
            $state.sourceCommit = $plan.commit
        }
        if ($plan.newFrontend) {
            $state.frontendRoot = $plan.frontendRoot
            if (!$plan.hmr) { $state.hotWebConfig = $viteConfig }
        }
        $state.hotMainCommit = $plan.commit
        Save-LocalState
    } catch {
        $reason = $_
        foreach ($key in 'sourceCommit','jarSha256','localJar','frontendRoot','hotWebConfig','hotMainCommit') {
            if ($originalMetadata.Contains($key)) { $state[$key] = $originalMetadata[$key] }
            else { $state.Remove($key) }
        }
        if ($newWeb) { Stop-Owned $newWeb }
        if ($newServer) { Stop-Owned $newServer }
        if ($hmrSwitched) { Set-WebCommit $oldRoot $plan.previousWebCommit }
        if ($apiStopped) { [void](Start-Server $oldJar ($oldJar -ne (Join-Path $service 'app.jar'))) }
        if ($webStopped) { [void](Start-Web $oldRoot $oldConfig $oldNode) }
        throw $reason
    }
} finally { $lock.Dispose() }
