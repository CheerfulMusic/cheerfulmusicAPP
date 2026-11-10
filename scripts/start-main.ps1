[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw '请使用 PowerShell 7 运行此脚本。' }
# Run from the installed <workspace>/scripts entry point.
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ((Split-Path $PSScriptRoot -Leaf) -ne 'scripts') { throw '请先将此脚本安装到项目 scripts 目录，再运行。' }
$runtimeBase = Join-Path $workspaceRoot '.cache\runtime'
$runtimeRoot = Join-Path $runtimeBase 'production-main'
$statePath = Join-Path $runtimeBase 'local.json'
$privatePath = Join-Path $workspaceRoot '.secrets\production-main.local.properties'
$manifestPath = Join-Path $workspaceRoot '.secrets\production-main.manifest.json'
$gatewayPath = Join-Path $workspaceRoot '.secrets\production-main.gateway.json'
$runtimeJar = Join-Path $runtimeRoot 'app.jar'
$sshKey = if ($env:QF_SSH_KEY_PATH) { $env:QF_SSH_KEY_PATH } else { Join-Path $workspaceRoot '.cache\deploy-ssh\id_ed25519' }
$knownHosts = Join-Path $workspaceRoot '.cache\deploy-ssh\known_hosts'
$runId = [Guid]::NewGuid().ToString('N')
$processes = @()
$lifecycleLock = $null

function Test-TrackedProcess($entry) {
    $running = Get-Process -Id ([int]$entry.id) -ErrorAction SilentlyContinue
    return $running -and $running.StartTime.ToUniversalTime().Ticks -eq [long]$entry.started
}
function ConvertTo-NativeArgument([string]$value) {
    $escaped = [regex]::Replace($value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
function Save-State {
    $state = @{
        version = 2; runId = $runId; mainDatabase = $true; mode = 'production-main'
        port = 8080; webPort = 5173; dev = $true
        sourceCommit = $manifest.sourceCommit; jarSha256 = $manifest.jarSha256
        frontendRoot = $frontendRoot; database = $manifest.database; processes = @($script:processes)
    }
    $temporary = Join-Path $runtimeBase ('local.' + $runId + '.tmp')
    [IO.File]::WriteAllText($temporary, ($state | ConvertTo-Json -Depth 7), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary, $statePath, $true)
}
function Start-Tracked([string]$file, [string[]]$arguments, [string]$directory, [string]$kind) {
    $commandLine = ($arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $child = Start-Process -FilePath $file -ArgumentList $commandLine -WorkingDirectory $directory -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runtimeRoot ($kind + '.out.log')) `
        -RedirectStandardError (Join-Path $runtimeRoot ($kind + '.err.log')) -PassThru
    $script:processes += @{ id = $child.Id; started = $child.StartTime.ToUniversalTime().Ticks; kind = $kind }
    Save-State
    return $child
}
function Test-TunnelPort {
    $client = [Net.Sockets.TcpClient]::new()
    try { $connect = $client.ConnectAsync('127.0.0.1', 13306); return $connect.Wait(500) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}
function Wait-Ready($child, [scriptblock]$probe, [int]$seconds, [string]$failure) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    do {
        $child.Refresh()
        if ($child.HasExited) { throw $failure }
        $ready = $false
        try { $ready = [bool](& $probe) } catch {}
        if ($ready) { return }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw $failure
}
function Test-BackendHealth {
    $health = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 2
    return $health.status -eq 'UP'
}
function Stop-ThisRun {
    $failed = $false
    $entries = @($script:processes); [Array]::Reverse($entries)
    foreach ($entry in $entries) {
        try { if (Test-TrackedProcess $entry) { Stop-Process -Id ([int]$entry.id) -ErrorAction Stop } }
        catch { $failed = $true }
    }
    if (-not $failed -and (Test-Path -LiteralPath $statePath)) {
        $current = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if ($current.runId -eq $runId) { Remove-Item -LiteralPath $statePath }
    }
    if ($failed) { Write-Warning '本次部分进程未能退出，记录已保留；请运行 scripts/stop-main.ps1。' }
}

try {
    foreach ($required in @($privatePath, $manifestPath, $runtimeJar, $sshKey, $knownHosts)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw '主库启动材料不完整，请先完成私有配置、清单和线上同版 JAR 的准备。' }
    }
    New-Item -ItemType Directory -Path $runtimeBase -Force | Out-Null
    try { $lifecycleLock = [IO.File]::Open((Join-Path $runtimeBase 'local.lifecycle.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw '另一启动或停止流程正在操作，请稍后重试。' }
    if (Test-Path -LiteralPath $statePath) {
        $prior = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        foreach ($entry in $prior.processes) {
            if (Test-TrackedProcess $entry) {
                if ($prior.mainDatabase) { throw '主库实例已启动，请先运行 scripts/stop-main.ps1。' }
                throw '检测到旧入口启动的进程，请先运行 scripts/stop-local.ps1。'
            }
        }
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.jarSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $manifest.sourceCommit -notmatch '^[a-fA-F0-9]{40}$') { throw '主库清单缺少有效的 JAR 哈希或源码提交。' }
    $db = $manifest.database
    if ($db.sshHost -ne '47.84.5.172' -or $db.remoteHost -ne '127.0.0.1' -or [int]$db.remotePort -ne 3306 -or [int]$db.localPort -ne 13306 -or $db.name -ne 'qingfeng_music') { throw '清单中的主库目标与已确认的生产主库不一致。' }
    if ((Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash -ine $manifest.jarSha256) { throw 'JAR 与清单中的线上版本哈希不一致，未启动。' }
    if (-not [IO.Path]::IsPathFullyQualified([string]$manifest.frontendRoot)) { throw '清单中的前端路径必须为绝对路径。' }
    $frontendRoot = (Resolve-Path -LiteralPath $manifest.frontendRoot).Path
    $vite = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $vite -PathType Leaf)) { throw '已核对前端的 Vite 依赖缺失，请先在该干净工作树安装依赖。' }
    $git = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $branch = (& $git -C $frontendRoot branch --show-current 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') { throw '清单中的前端必须位于 main 工作树。' }
    $prefix = (& $git -C $frontendRoot rev-parse --show-prefix 2>$null).Trim().TrimEnd('/')
    $gitRoot = (& $git -C $frontendRoot rev-parse --show-toplevel 2>$null).Trim()
    $changes = @(& $git -C $gitRoot status --porcelain --untracked-files=all -- $prefix 2>$null)
    if ($LASTEXITCODE -ne 0 -or $changes.Count -gt 0) { throw 'main 前端存在未提交改动，未启动；请先核对清单对应的干净前端。' }
    $productionTree = (& $git -C $gitRoot rev-parse ($manifest.sourceCommit + ':' + $prefix) 2>$null).Trim()
    if ($LASTEXITCODE -ne 0) { throw '无法核对线上提交的前端目录。' }
    $localTree = (& $git -C $gitRoot rev-parse ('HEAD:' + $prefix) 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $productionTree -ne $localTree) { throw 'main 前端目录与线上源码提交不一致，未启动。' }

    # Validate only the JDBC address; never print the properties file or credentials.
    $jdbcValues = @()
    foreach ($line in [IO.File]::ReadAllLines($privatePath)) {
        if ($line -match '^\s*spring\.datasource\.url\s*[:=]\s*(.*)$') { $jdbcValues += $Matches[1].Replace('\:', ':').Replace('\=', '=') }
        if ($line -match '^\s*spring\.config\.(import|location|additional-location)\s*[:=]') { throw '主库私有配置不可再隐式导入其他配置文件。' }
    }
    if ($jdbcValues.Count -ne 1) { throw '私有配置须明确且仅设置一个主库 JDBC 地址。' }
    try { $jdbc = [Uri]($jdbcValues[0] -replace '^jdbc:', '') }
    catch { throw '主库 JDBC 地址格式无效，未启动。' }
    if (-not $jdbc.IsAbsoluteUri -or $jdbc.Scheme -ne 'mysql' -or $jdbc.Host -ne '127.0.0.1' -or $jdbc.Port -ne 13306 -or $jdbc.AbsolutePath -ne '/qingfeng_music' -or $jdbc.UserInfo) { throw '私有配置必须连接已确认的主库隧道地址，未启动。' }
    # The packaged mysql profile has these optional relative imports. Refuse stale local files.
    foreach ($directory in @((Join-Path $runtimeRoot 'config'), (Join-Path $runtimeRoot 'Qingfeng_AI_Music'), (Join-Path $runtimeRoot '..\config'))) {
        if (Test-Path -LiteralPath $directory) { throw '独立运行目录附近存在旧 config 目录，请先人工核对；未读取或覆盖它。' }
    }
    foreach ($file in @('/etc/qingfeng-music/usage-limits.properties', '/etc/qingfeng-music/invite-gate.properties')) {
        if (Test-Path -LiteralPath $file) { throw '检测到会被隐式导入的本机 /etc 配置，请先人工核对。' }
    }
    foreach ($checkPort in @(13306, 8080, 5173)) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $checkPort)
        try { $listener.Start() } catch { throw "端口 $checkPort 已被占用，未停止任何现有程序。" } finally { $listener.Stop() }
    }
    $java = if(Test-Path -LiteralPath 'D:/jdk17/bin/java.exe'){'D:/jdk17/bin/java.exe'}else{(Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source}
    $node = (Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $ssh = (Get-Command ssh -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $javaVersion = (& $java -version 2>&1) -join ' '
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?:version\s+"|openjdk\s+|java\s+)17(?:[.\s"]|$)') { throw '此入口要求 Java 17，请先核对 PATH 指向的 Java。' }
    $gatewayReady=$false
    if(Test-Path -LiteralPath $gatewayPath){$gateway=Get-Content -LiteralPath $gatewayPath -Raw|ConvertFrom-Json;$gatewayReady=$gateway.upstream -eq 'https://cheerfulmusic.com' -and [long]$gateway.expiresAt -gt [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+300}
    if(-not $gatewayReady){$python=(Get-Command python -CommandType Application -ErrorAction Stop|Select-Object -First 1).Source;& $python (Join-Path $workspaceRoot 'scripts/prepare-main-gateway.py');if($LASTEXITCODE -ne 0){throw '线上业务接口通行配置未准备完成。'}}

    $tunnel = Start-Tracked $ssh @('-N', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', "UserKnownHostsFile=$knownHosts", '-o', 'ExitOnForwardFailure=yes', '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=30', '-o', 'ServerAliveCountMax=3', '-L', '127.0.0.1:13306:127.0.0.1:3306', '-i', $sshKey, 'root@47.84.5.172') $runtimeRoot 'ssh'
    Wait-Ready $tunnel { Test-TunnelPort } 20 '主库 SSH 隧道启动失败，请查看 .cache/runtime/production-main/ssh.err.log。'
    $privateUri = ([Uri]$privatePath).AbsoluteUri
    $serverArguments = @('-Xms64m', '-Xmx512m', '-jar', $runtimeJar, '--spring.profiles.active=mysql', '--spring.profiles.include=', '--spring.config.location=classpath:/', "--spring.config.additional-location=$privateUri", '--server.address=127.0.0.1', '--server.port=8080', '--spring.flyway.enabled=false', '--spring.sql.init.mode=never', '--qingfeng.quick.scheduler-enabled=false', '--qingfeng.generation.scheduler-enabled=false', '--qingfeng.cover.ai.scheduler-enabled=false', '--qingfeng.payment-demo.enabled=false', '--qingfeng.payments.enabled=false', '--qingfeng.paypal.enabled=false', '--qingfeng.paypal.live-enabled=false', '--qingfeng.paypal.reconciliation.enabled=false', '--qingfeng.auth.sms-bridge.enabled=false')
    $removedEnvironment = @{}
    try {
        foreach ($item in Get-ChildItem Env: | Where-Object { $_.Name -match '^(SPRING[_.]|MYSQL_|QINGFENG[_.])|^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$' }) {
            $removedEnvironment[$item.Name] = $item.Value; [Environment]::SetEnvironmentVariable($item.Name, $null, 'Process')
        }
        $server = Start-Tracked $java $serverArguments $runtimeRoot 'server'
    } finally { foreach ($key in $removedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $removedEnvironment[$key], 'Process') } }
    Wait-Ready $server { Test-BackendHealth } 100 '后端未健康启动，请查看 .cache/runtime/production-main/server.out.log 与 server.err.log。'

    # Load only the installed Vue plugin. Ignore all old Vite dotenv files and force the loopback proxy.
    $frontendJson = ConvertTo-Json -InputObject $frontendRoot -Compress
    $gatewayJson = ConvertTo-Json -InputObject $gatewayPath -Compress
    $safeViteConfig = @"
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readFileSync } from 'node:fs';
const frontendRoot = $frontendJson;
const gateway = JSON.parse(readFileSync($gatewayJson, 'utf8'));
if (gateway.upstream !== 'https://cheerfulmusic.com') throw new Error('Invalid main backend');
const require = createRequire(pathToFileURL(frontendRoot + '/package.json'));
const vue = (await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)).default;
const target = process.env.VITE_API_PROXY_TARGET;
if (target !== 'http://127.0.0.1:8080') throw new Error('Invalid main database API proxy');
const production = {
  target: gateway.upstream, changeOrigin: true, secure: true,
  configure(proxy) {
    proxy.on('proxyReq', (outgoing, incoming) => {
      const pieces = String(incoming.headers.cookie || '').split(';').map(x => x.trim()).filter(x => x && !x.startsWith('QF_BETA_ACCESS='));
      if (gateway.inviteCookie) pieces.push(gateway.inviteCookie);
      if (pieces.length) outgoing.setHeader('cookie', pieces.join('; '));
      outgoing.setHeader('origin', gateway.upstream);
    });
  }
};
export default {
  root: frontendRoot, base: '/', envDir: false, plugins: [vue()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, cors: false,
    allowedHosts: ['127.0.0.1', 'localhost'], fs: { strict: true, allow: [frontendRoot] },
    proxy: {
      '^/api/app(?:/|[?]|$)': production,
      '^/api/public/(?!(?:auth|session|csrf|invite)(?:/|[?]|$))': production,
      '/media': production,
      '/api': { target, changeOrigin: true }
    } }
};
"@
    $viteConfigPath = Join-Path $runtimeRoot 'vite.production-main.config.mjs'
    [IO.File]::WriteAllText($viteConfigPath, $safeViteConfig, [Text.UTF8Encoding]::new($false))
    $previousTarget = $env:VITE_API_PROXY_TARGET
    $previousApiBase = $env:VITE_API_BASE_URL
    try {
        $env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:8080'
        $env:VITE_API_BASE_URL = '/api'
        $web = Start-Tracked $node @($vite, '--config', $viteConfigPath, '--configLoader', 'native', '--host', '127.0.0.1', '--port', '5173', '--strictPort') $frontendRoot 'web'
    } finally { $env:VITE_API_PROXY_TARGET = $previousTarget; $env:VITE_API_BASE_URL = $previousApiBase }
    Wait-Ready $web {
        (Invoke-WebRequest 'http://127.0.0.1:5173/' -TimeoutSec 2).StatusCode -eq 200 -and (Invoke-WebRequest 'http://127.0.0.1:5173/@vite/client' -TimeoutSec 2).StatusCode -eq 200
    } 60 '前端未健康启动，请查看 .cache/runtime/production-main/web.err.log。'
    foreach ($entry in $processes) { if (-not (Test-TrackedProcess $entry)) { throw '启动进程已退出，未宣告启动成功。' } }
    if (-not (Test-TunnelPort) -or -not (Test-BackendHealth)) { throw '最终隧道或后端健康检查未通过。' }
    Save-State
    Write-Host '已启动线上主库日常入口：http://127.0.0.1:5173（API：http://127.0.0.1:8080）。'
    Write-Host '数据库经本机 SSH 隧道连接已确认的生产主库；停止请运行 scripts/stop-main.ps1。'
} catch {
    Stop-ThisRun
    throw
} finally {
    if ($lifecycleLock) { $lifecycleLock.Dispose() }
}
