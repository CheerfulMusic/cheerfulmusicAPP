[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7){throw '请使用 PowerShell 7。'}
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeBase=Join-Path $workspaceRoot '.cache\runtime'
$runtimeRoot=Join-Path $runtimeBase 'production-main'
$statePath=Join-Path $runtimeBase 'local.json'
$frontendRoot=Join-Path $workspaceRoot 'Qingfeng_AI_Music_VUE'
$vite=Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
$oldConfig=Join-Path $runtimeRoot 'vite.production-main.config.mjs'
$newConfig=Join-Path $runtimeRoot 'vite.frontend-preview.config.mjs'
$lifecycle=$null;$newWeb=$null;$oldStopped=$false;$state=$null
function Matches($entry){$p=Get-Process -Id ([int]$entry.id) -ErrorAction SilentlyContinue;return $p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$entry.started}
function Quote-Argument([string]$value){return '"'+[regex]::Replace([regex]::Replace($value,'(\\*)"','$1$1\"'),'(\\+)$','$1$1')+'"'}
function Launch-Web([string]$config,[string]$directory,[string]$name){
    $arguments=@($vite,'--config',$config,'--configLoader','native','--host','127.0.0.1','--port','5173','--strictPort')
    $commandLine=($arguments|ForEach-Object{Quote-Argument $_}) -join ' '
    return Start-Process -FilePath $node -ArgumentList $commandLine -WorkingDirectory $directory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeRoot ($name+'.out.log')) -RedirectStandardError (Join-Path $runtimeRoot ($name+'.err.log'))
}
function Save-WebState($web,[bool]$preview){
    $entries=@($state.processes|Where-Object{$_.kind -ne 'web'})
    $entries+=@{id=$web.Id;started=$web.StartTime.ToUniversalTime().Ticks;kind='web'}
    $state.processes=$entries
    $state.mode=if($preview){'frontend-preview'}else{'production-main'}
    $state.frontendRoot=if($preview){$frontendRoot}else{$oldFrontendRoot}
    $state|Add-Member -NotePropertyName frontendSourceCommit -NotePropertyValue $(if($preview){(& git -C $workspaceRoot rev-parse HEAD).Trim()}else{$state.sourceCommit}) -Force
    $temporary=Join-Path $runtimeBase ('preview.'+[Guid]::NewGuid().ToString('N')+'.tmp')
    [IO.File]::WriteAllText($temporary,($state|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary,$statePath,$true)
}
$previousTarget=$env:VITE_API_PROXY_TARGET;$previousBase=$env:VITE_API_BASE_URL
try{
    if(-not(Test-Path -LiteralPath $statePath) -or -not(Test-Path -LiteralPath $oldConfig) -or -not(Test-Path -LiteralPath $vite)){throw '请先准备日常运行入口和前端依赖。'}
    $lifecycle=[IO.File]::Open((Join-Path $runtimeBase 'local.lifecycle.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $state=Get-Content -Raw -LiteralPath $statePath|ConvertFrom-Json
    if(-not $state.mainDatabase -or $state.port -ne 8080 -or $state.webPort -ne 5173){throw '当前运行记录不是已确认的主库日常入口，未停止任何进程。'}
    foreach($kind in @('server','ssh')){
        $entry=@($state.processes|Where-Object{$_.kind -eq $kind})
        if($entry.Count -ne 1 -or -not(Matches $entry[0])){throw '现有登录后端或隧道未运行，未停止任何进程。'}
    }
    if((Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5).status -ne 'UP'){throw '现有后端未就绪。'}
    $oldWeb=@($state.processes|Where-Object{$_.kind -eq 'web'})
    if($oldWeb.Count -ne 1 -or -not(Matches $oldWeb[0])){throw '旧前端进程与记录不匹配，未停止其他进程。'}
    $listener=@(Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)
    if($listener.Count -ne 1 -or $listener[0].OwningProcess -ne $oldWeb[0].id -or $listener[0].LocalAddress -ne '127.0.0.1'){throw '5173端口归属未通过核对。'}
    $node=(Get-Command node -CommandType Application -ErrorAction Stop|Select-Object -First 1).Source
    $oldFrontendRoot=$state.frontendRoot
    Copy-Item -LiteralPath $statePath -Destination (Join-Path $runtimeBase ('local.before-preview.'+[DateTime]::Now.ToString('yyyyMMdd-HHmmss')+'.json'))
    # 沿用已审定的 API / 登录代理，只将前端 root 改为当前工作区。私有通行值不会写入脚本。
    $config=[IO.File]::ReadAllText($oldConfig)
    $rootPattern='(?m)^const frontendRoot = ([^\r\n]*);\r?$'
    $rootMatches=[regex]::Matches($config,$rootPattern)
    if($rootMatches.Count -ne 1){throw '现有前端代理配置格式不匹配。'}
    $oldFrontendRoot=$rootMatches[0].Groups[1].Value|ConvertFrom-Json
    $newRootLine='const frontendRoot = '+(ConvertTo-Json -InputObject $frontendRoot -Compress)+';'
    $config=[regex]::Replace($config,$rootPattern,[System.Text.RegularExpressions.MatchEvaluator]{param($m) $newRootLine})
    [IO.File]::WriteAllText($newConfig,$config,[Text.UTF8Encoding]::new($false))
    & $node (Join-Path $PSScriptRoot 'preview-work-routing.mjs') $newConfig $newConfig
    if($LASTEXITCODE -ne 0){throw '作品接口预览路由准备失败，未停止原前端。'}
    Stop-Process -Id ([int]$oldWeb[0].id)
    $oldStopped=$true
    $deadline=[DateTime]::UtcNow.AddSeconds(5)
    while((Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 150}
    $env:VITE_API_PROXY_TARGET='http://127.0.0.1:8080';$env:VITE_API_BASE_URL='/api'
    $newWeb=Launch-Web $newConfig $frontendRoot 'web-preview'
    $ready=$false;$deadline=[DateTime]::UtcNow.AddSeconds(45)
    do{
        $newWeb.Refresh();if($newWeb.HasExited){throw '新版前端进程退出，请检查 web-preview.err.log。'}
        try{
            $module=Invoke-WebRequest 'http://127.0.0.1:5173/src/modules/studio/pages/DraftStudioPage.vue' -TimeoutSec 2
            $ready=$module.StatusCode -eq 200 -and $module.Content.Contains('creation-mode-switch')
        }catch{}
        if(-not $ready){Start-Sleep -Milliseconds 300}
    }while(-not $ready -and [DateTime]::UtcNow -lt $deadline)
    if(-not $ready){throw '新版页面未通过读取检查。'}
    Save-WebState $newWeb $true
    Write-Host '新版前端已启动：http://127.0.0.1:5173/create'
    Write-Host '读取当前工作区源码；作品列表与保存删除走本机后端，音频封面仍沿用线上接口。业务能力以本机后端实际返回为准。'
}catch{
    if($newWeb){$newWeb.Refresh();if(-not $newWeb.HasExited){Stop-Process -Id $newWeb.Id}}
    if($oldStopped){
        try{$env:VITE_API_PROXY_TARGET='http://127.0.0.1:8080';$env:VITE_API_BASE_URL='/api';$restored=Launch-Web $oldConfig $oldFrontendRoot 'web-restored';Save-WebState $restored $false;Write-Warning '新版启动失败，已恢复旧前端。'}
        catch{Write-Warning '自动恢复未完成；现有登录后端和隧道未被停止。'}
    }
    throw
}finally{
    $env:VITE_API_PROXY_TARGET=$previousTarget;$env:VITE_API_BASE_URL=$previousBase
    if($lifecycle){$lifecycle.Dispose()}
}
