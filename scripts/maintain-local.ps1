param([switch]$Quarantine,[switch]$RepairCapacity)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalTools.ps1')
Assert-LocalStopped
$context=$null
try{
    $context=New-MySqlContext;$audioRoot=Get-AudioDirectory $context
    $keys=(Invoke-MySql $context 'SELECT storage_key FROM media_asset' $context.database) -split "`r?`n"
    $references=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($key in $keys){if($key.StartsWith('oss:')){continue};[void]$references.Add($key.Replace('local:',''))}
    $candidates=@();if(Test-Path -LiteralPath $audioRoot){$candidates=@(Get-ChildItem -LiteralPath $audioRoot -File | Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -and ($_.Name -match '^([0-9a-f-]{36}\.(wav|mp3)|upload-.*\.part)$') -and $_.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddHours(-24) -and -not $references.Contains($_.Name) })}
    Write-Host "超过24小时且无数据库引用的本地文件：$($candidates.Count)"
    if($Quarantine -and $candidates.Count){
        $workspacePrefix=$script:TaskRoot+[IO.Path]::DirectorySeparatorChar
        if(-not $audioRoot.StartsWith($workspacePrefix,[StringComparison]::OrdinalIgnoreCase)){throw '自动隔离仅允许当前工作区内的音频目录。'}
        $destination=Join-Path $script:TaskRoot ('.cache\quarantine\'+(Get-Date -Format 'yyyyMMdd-HHmmss'));New-Item -ItemType Directory -Path $destination | Out-Null
        foreach($file in $candidates){$source=[IO.Path]::GetFullPath($file.FullName);$target=[IO.Path]::GetFullPath((Join-Path $destination $file.Name));if(-not $source.StartsWith($audioRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not $target.StartsWith($workspacePrefix,[StringComparison]::OrdinalIgnoreCase)){throw '隔离路径校验失败。'};Move-Item -LiteralPath $source -Destination $target}
        Write-Host "已移动到可恢复的隔离目录：$destination"
    }
    if($RepairCapacity){[void](Invoke-MySql $context 'UPDATE creator SET media_bytes=COALESCE((SELECT SUM(m.byte_size) FROM media_asset m JOIN music_work w ON m.work_id=w.id WHERE w.owner_id=creator.id),0)' $context.database);Write-Host '已按已登记音频重新核算容量，释放异常退出遗留的预留额度。'}
    if(-not $Quarantine){Write-Host '当前仅检查；使用 -Quarantine 将孤儿文件移入隔离目录，不永久删除。'}
}finally{Close-MySqlContext $context}
