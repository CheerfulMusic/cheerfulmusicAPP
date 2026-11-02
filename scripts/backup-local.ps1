param([switch]$Online)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalTools.ps1')
if(-not $Online){Assert-LocalStopped}
$context=$null
try {
    $context=New-MySqlContext
    $audioRoot=Get-AudioDirectory $context
    $registered=(Invoke-MySql $context 'SELECT storage_key,byte_size,sha256 FROM media_asset' $context.database) -split "`r?`n"
    foreach($row in $registered){if(-not $row){continue};$parts=$row -split "`t";if($parts[0].StartsWith('oss:')){throw '存在云端媒体引用，请先备份OSS对象，不能把本地副本当完整备份。'};$name=$parts[0].Replace('local:','');if($name -notmatch '^[0-9a-f-]{36}\.(wav|mp3)$'){throw '媒体记录格式异常，未生成完整备份。'};$file=Join-Path $audioRoot $name;if(-not(Test-Path -LiteralPath $file) -or (Get-Item -LiteralPath $file).Length -ne [long]$parts[1] -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $parts[2]){throw '已登记音频缺失或校验不符，请先恢复文件后再备份。'}}
    $backupRoot=Join-Path $script:TaskRoot ('.backups\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6));New-Item -ItemType Directory -Path $backupRoot | Out-Null
    $sqlFile=Join-Path $backupRoot 'database.sql'
    & $context.dump ('--defaults-extra-file='+$context.file) '--single-transaction' '--skip-lock-tables' '--no-tablespaces' '--set-gtid-purged=OFF' '--column-statistics=0' '--hex-blob' ('--result-file='+$sqlFile) $context.database 2> (Join-Path $backupRoot 'dump.err.log')
    if($LASTEXITCODE -ne 0){throw '数据库备份失败，未生成完成标记。'}
    $audioRoot=Get-AudioDirectory $context;$mediaDestination=Join-Path $backupRoot 'audio';New-Item -ItemType Directory -Path $mediaDestination | Out-Null
    if(Test-Path -LiteralPath $audioRoot){foreach($file in Get-ChildItem -LiteralPath $audioRoot -File){if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw '音频目录包含链接文件，请先检查。'};if($file.Name -match '^[0-9a-f-]{36}\.(wav|mp3)$'){Copy-Item -LiteralPath $file.FullName -Destination $mediaDestination}}}
    $hashes=@{};foreach($file in Get-ChildItem -LiteralPath $backupRoot -File -Recurse){if($file.Name -eq 'dump.err.log'){continue};$relative=[IO.Path]::GetRelativePath($backupRoot,$file.FullName);$hashes[$relative]=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
    @{format=1;createdAt=[DateTime]::UtcNow.ToString('o');database=$context.database;hashes=$hashes} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backupRoot 'manifest.json') -Encoding utf8
    Write-Host "完整本地备份已生成：$backupRoot"
    Write-Host '包含私人作品数据和音频，请保密保存；不包含API密钥或数据库密码。'
    if($Online){Write-Host '在线备份仅适用于当前媒体不可变、没有并行文件整理的版本；任务恢复仍以持久化状态为准。'}
} finally {Close-MySqlContext $context}
