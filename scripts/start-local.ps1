param([switch]$Dev,[switch]$Lan,[int]$Port=8080,[int]$WebPort=5173)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeRoot = Join-Path $workspaceRoot '.cache\runtime'
$statePath = Join-Path $runtimeRoot 'local.json'
$serverJar = Join-Path $workspaceRoot 'Qingfeng_AI_Music\qf-server\target\qf-server-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $serverJar)) { throw '请先运行 scripts/build-local.ps1。' }
if ($Port -lt 1024 -or $Port -gt 65535 -or $WebPort -lt 1024 -or $WebPort -gt 65535) { throw '端口范围无效。' }
if (Test-Path -LiteralPath $statePath) { $prior=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json; foreach($entry in $prior.processes) { $running=Get-Process -Id $entry.id -ErrorAction SilentlyContinue; if ($running -and $running.StartTime.ToUniversalTime().Ticks -eq $entry.started) { throw '本项目服务已启动，请先运行 scripts/stop-local.ps1。' } } }
foreach($checkPort in $(if($Dev){@($Port,$WebPort)}else{@($Port)})) { $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$checkPort);try{$listener.Start()}catch{throw "端口 $checkPort 已被使用，请先确认现有程序或更换端口。"}finally{$listener.Stop()} }
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$processes=@()
function Save-State { @{processes=$script:processes;port=$Port;webPort=$WebPort;dev=[bool]$Dev} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding utf8 }
try {
    $bindAddress=if($Lan -and -not $Dev){'0.0.0.0'}else{'127.0.0.1'}
    # Windows运行中的Jar可能被锁定，使用运行副本，让构建输出可以独立更新。
    $runtimeJar=Join-Path $runtimeRoot 'qingfeng-server-running.jar'
    Copy-Item -LiteralPath $serverJar -Destination $runtimeJar -Force
    $server=Start-Process -FilePath (Get-Command java -ErrorAction Stop).Source -ArgumentList @('-jar',('"'+$runtimeJar+'"'),'--spring.profiles.active=mysql',"--server.port=$Port","--server.address=$bindAddress") -WorkingDirectory (Join-Path $workspaceRoot 'Qingfeng_AI_Music') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeRoot 'server.out.log') -RedirectStandardError (Join-Path $runtimeRoot 'server.err.log') -PassThru
    $processes+=@{id=$server.Id;started=$server.StartTime.ToUniversalTime().Ticks};Save-State
    $ready=$false
    for($attempt=0;$attempt -lt 100;$attempt++){if($server.HasExited){throw '后端启动失败，请查看 .cache/runtime/server.out.log。'};try{$ready=(Invoke-WebRequest "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 1 -UseBasicParsing).StatusCode -eq 200}catch{};if($ready){break};Start-Sleep -Milliseconds 300}
    if(-not $ready){throw '后端启动超时，请检查本机MySQL及本地配置。'}
    if($Dev){
        $node=(Get-Command node -ErrorAction Stop).Source;$vite=Join-Path $workspaceRoot 'Qingfeng_AI_Music_VUE\node_modules\vite\bin\vite.js';$webBind=if($Lan){'0.0.0.0'}else{'127.0.0.1'}
        $previousTarget=$env:VITE_API_PROXY_TARGET
        try{$env:VITE_API_PROXY_TARGET="http://127.0.0.1:$Port";$web=Start-Process -FilePath $node -ArgumentList @(('"'+$vite+'"'),'--host',$webBind,'--port',$WebPort,'--strictPort') -WorkingDirectory (Join-Path $workspaceRoot 'Qingfeng_AI_Music_VUE') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeRoot 'web.out.log') -RedirectStandardError (Join-Path $runtimeRoot 'web.err.log') -PassThru}finally{$env:VITE_API_PROXY_TARGET=$previousTarget}
        $processes+=@{id=$web.Id;started=$web.StartTime.ToUniversalTime().Ticks};Save-State
        Write-Host "开发网页：http://127.0.0.1:$WebPort"
    }else{Write-Host "本地完整网页：http://127.0.0.1:$Port"}
    if($Lan){Write-Host '手机请连接同一局域网，用电脑局域网IP和上述网页端口访问；如被系统防火墙拦截，请仅允许所需的专用网络。'}
    Write-Host '后台已启动；停止请运行 scripts/stop-local.ps1。模型调用默认关闭。'
}catch{foreach($entry in $processes){$running=Get-Process -Id $entry.id -ErrorAction SilentlyContinue;if($running -and $running.StartTime.ToUniversalTime().Ticks -eq $entry.started){Stop-Process -Id $entry.id}};throw}
