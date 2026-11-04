param([Parameter(Mandatory=$true)][string]$BackupPath,[string]$TargetDatabase=('qingfeng_music_restore_'+(Get-Date -Format 'yyyyMMddHHmmss')))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalTools.ps1')
# 恢复只创建独立新库和新目录，可与原项目并行，不切换或覆盖现有数据。
$context=$null
try {
    if($TargetDatabase -notmatch '^qingfeng_music_restore_[a-z0-9_]{1,32}$'){throw '恢复仅允许新的qingfeng_music_restore_前缀数据库，不覆盖原库。'}
    $backupRoot=[IO.Path]::GetFullPath($BackupPath);$allowed=Join-Path $script:TaskRoot '.backups\';if(-not $backupRoot.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw '请选择本项目.backups目录中的备份。'}
    $manifest=Get-Content -LiteralPath (Join-Path $backupRoot 'manifest.json') -Raw | ConvertFrom-Json -AsHashtable
    if($manifest.format -ne 1 -or $manifest.database -ne 'qingfeng_music' -or -not $manifest.hashes.ContainsKey('database.sql')){throw '备份格式无效。'}
    foreach($entry in $manifest.hashes.GetEnumerator()){$file=[IO.Path]::GetFullPath((Join-Path $backupRoot $entry.Key));if(-not $file.StartsWith($backupRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw '备份清单路径越界。'};if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.Value){throw '备份完整性检查失败，未写入数据库。'}}
    $sqlFile=Join-Path $backupRoot 'database.sql';if(Select-String -LiteralPath $sqlFile -Pattern '^\s*(USE\s|CREATE\s+DATABASE|DROP\s+DATABASE)' -Quiet){throw '备份包含切换数据库指令，拒绝恢复。'}
    $context=New-MySqlContext
    $exists=Invoke-MySql $context ("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+$TargetDatabase+"'")
    if($exists -ne '0'){throw '目标数据库已存在，未覆盖。'}
    [void](Invoke-MySql $context ('CREATE DATABASE '+$TargetDatabase+' CHARACTER SET utf8mb4'))
    [void](Invoke-MySql $context '' $TargetDatabase $sqlFile)
    $hasCoverTable=Invoke-MySql $context "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='cover_asset'" $TargetDatabase
    [void](Invoke-MySql $context 'UPDATE creator SET media_bytes=COALESCE((SELECT SUM(m.byte_size) FROM media_asset m JOIN music_work w ON m.work_id=w.id WHERE w.owner_id=creator.id),0)' $TargetDatabase)
    if($hasCoverTable -eq '1'){[void](Invoke-MySql $context 'UPDATE creator SET media_bytes=media_bytes+COALESCE((SELECT SUM(c.byte_size) FROM cover_asset c JOIN music_work w ON c.work_id=w.id WHERE w.owner_id=creator.id),0)' $TargetDatabase)}
    $restoreRoot=Join-Path $script:TaskRoot ('.cache\restores\'+$TargetDatabase);if(Test-Path -LiteralPath $restoreRoot){throw '恢复目录已存在，未覆盖。'};New-Item -ItemType Directory -Path $restoreRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $backupRoot 'audio') -Destination $restoreRoot -Recurse
    if(Test-Path -LiteralPath (Join-Path $backupRoot 'covers')){Copy-Item -LiteralPath (Join-Path $backupRoot 'covers') -Destination $restoreRoot -Recurse;Write-Host "对应封面目录：$(Join-Path $restoreRoot 'covers')，切换验证时需同时配置qingfeng.cover.directory。"}
    Write-Host "已恢复到独立数据库：$TargetDatabase"
    Write-Host "对应音频目录：$(Join-Path $restoreRoot 'audio')"
    Write-Host '原数据库和原音频保持原样。切换验证时请同时调整数据库URL与音频目录。'
}finally{Close-MySqlContext $context}
