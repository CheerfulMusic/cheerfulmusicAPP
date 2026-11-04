$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7){throw '维护脚本需要PowerShell 7，请使用pwsh执行。'}
$script:TaskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Read-LocalConfig {
    $values=@{};$config=Join-Path $script:TaskRoot 'Qingfeng_AI_Music\config\local.properties'
    if(Test-Path -LiteralPath $config){foreach($line in [IO.File]::ReadAllLines($config)){if($line -match '^\s*([^#!\s][^=]*?)\s*=(.*)$'){$values[$Matches[1].Trim()]=$Matches[2].TrimStart().Replace('\:',':').Replace('\=','=').Replace('\\','\')}}}
    return $values
}
function Assert-LocalStopped {
    $statePath=Join-Path $script:TaskRoot '.cache\runtime\local.json'
    if(Test-Path -LiteralPath $statePath){$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json;foreach($entry in $state.processes){$process=Get-Process -Id $entry.id -ErrorAction SilentlyContinue;if($process -and $process.StartTime.ToUniversalTime().Ticks -eq $entry.started){throw '维护前请运行 scripts/stop-local.ps1，确保备份和文件整理期间没有写入。'}}}
    foreach($port in @(8080,18080)){try{$system=Invoke-RestMethod "http://127.0.0.1:$port/api/public/system/info" -TimeoutSec 1;if($system.name -eq '青风音乐' -and $system.stage -ne 'SCAFFOLD'){throw [InvalidOperationException]::new("本项目后端仍在端口 $port 运行，请先停止后再维护。")}}catch [InvalidOperationException]{throw}catch{}}
}
function New-MySqlContext {
    $values=Read-LocalConfig
    $url=if($values.ContainsKey('spring.datasource.url')){$values['spring.datasource.url']}elseif($env:MYSQL_URL){$env:MYSQL_URL}else{'jdbc:mysql://127.0.0.1:3306/qingfeng_music'}
    if(-not $url.StartsWith('jdbc:mysql://')){throw '维护脚本仅支持本地MySQL配置。'}
    $uri=[Uri]$url.Substring(5);$database=$uri.AbsolutePath.Trim('/')
    if($uri.Host -notin @('127.0.0.1','localhost','[::1]') -or $database -ne 'qingfeng_music'){throw '维护工具仅操作本机独立qingfeng_music库。'}
    $username=if($values.ContainsKey('spring.datasource.username')){$values['spring.datasource.username']}else{$env:MYSQL_USERNAME}
    $password=if($values.ContainsKey('spring.datasource.password')){$values['spring.datasource.password']}else{$env:MYSQL_PASSWORD}
    if([string]::IsNullOrWhiteSpace($username)){throw '请先配置数据库用户名。'}
    $clientDir=Join-Path $script:TaskRoot '.cache\maintenance';New-Item -ItemType Directory -Path $clientDir -Force | Out-Null
    $clientFile=Join-Path $clientDir ('client-'+[Guid]::NewGuid().ToString('N')+'.cnf')
    function Escape-ClientValue([string]$value){if($value.Contains("`n") -or $value.Contains("`r")){throw '数据库配置值包含不支持的换行。'};return $value.Replace('\','\\').Replace('"','\"')}
    $content="[client]`nhost="+$uri.Host+"`nport="+$(if($uri.Port -gt 0){$uri.Port}else{3306})+"`nuser=`""+(Escape-ClientValue $username)+"`"`npassword=`""+(Escape-ClientValue $password)+"`"`nprotocol=TCP`ndefault-character-set=utf8mb4`n"
    [IO.File]::WriteAllText($clientFile,$content,[Text.UTF8Encoding]::new($false))
    $acl=Get-Acl -LiteralPath $clientFile;$acl.SetAccessRuleProtection($true,$false);$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User;$acl.SetAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow'));Set-Acl -LiteralPath $clientFile -AclObject $acl
    $mysql=(Get-Command mysql.exe -ErrorAction SilentlyContinue).Source
    if(-not $mysql){$mysql='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'}
    if(-not(Test-Path -LiteralPath $mysql)){Remove-Item -LiteralPath $clientFile;throw '未找到本机mysql.exe，请安装MySQL客户端并加入PATH。'}
    return @{file=$clientFile;mysql=$mysql;dump=(Join-Path (Split-Path $mysql) 'mysqldump.exe');database=$database;config=$values}
}
function Close-MySqlContext($context){if($context -and (Test-Path -LiteralPath $context.file)){$resolved=[IO.Path]::GetFullPath($context.file);$expected=Join-Path $script:TaskRoot '.cache\maintenance\';if(-not $resolved.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase)){throw '临时配置路径校验失败。'};Remove-Item -LiteralPath $resolved}}
function Invoke-MySql($context,[string]$sql,[string]$database='',[string]$inputFile='') {
    $info=[Diagnostics.ProcessStartInfo]::new($context.mysql);$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($argument in @(('--defaults-extra-file='+$context.file),'--batch','--raw','--skip-column-names')){$info.ArgumentList.Add($argument)}
    if($database){$info.ArgumentList.Add($database)}
    if($inputFile){$info.RedirectStandardInput=$true}else{$info.ArgumentList.Add('--execute='+$sql)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;[void]$process.Start();$output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
    if($inputFile){$stream=[IO.File]::OpenRead($inputFile);try{$stream.CopyTo($process.StandardInput.BaseStream)}finally{$stream.Dispose();$process.StandardInput.Close()}}
    $process.WaitForExit();$result=$output.GetAwaiter().GetResult();[void]$errors.GetAwaiter().GetResult();if($process.ExitCode -ne 0){throw '本机MySQL操作失败，请检查配置、客户端版本和数据库状态。'};return $result.TrimEnd()
}
function Get-AudioDirectory($context){$value=$context.config['qingfeng.media.directory'];if(-not $value){$value='./data/audio'};if(-not [IO.Path]::IsPathRooted($value)){$value=Join-Path (Join-Path $script:TaskRoot 'Qingfeng_AI_Music') $value};return [IO.Path]::GetFullPath($value)}

function Get-CoverDirectory($context){$value=$context.config['qingfeng.cover.directory'];if(-not $value){$value=Join-Path ([IO.Path]::GetDirectoryName((Get-AudioDirectory $context))) 'covers'};if(-not [IO.Path]::IsPathRooted($value)){$value=Join-Path (Join-Path $script:TaskRoot 'Qingfeng_AI_Music') $value};return [IO.Path]::GetFullPath($value)}
