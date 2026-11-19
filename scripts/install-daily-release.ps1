param(
    [Parameter(Mandatory)][string]$Scratch,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$CacheRoot,

    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
if ((Get-TimeZone).Id -ne 'China Standard Time') {
    throw 'This installer expects the Windows time zone China Standard Time.'
}
$python = (Get-Command python.exe -CommandType Application | Select-Object -First 1).Source
$items = @((Join-Path $PSScriptRoot 'daily-release.py'), '--scratch', $Scratch,
    '--java-home', (Resolve-Path -LiteralPath $JavaHome).Path,
    '--cache-root', (Resolve-Path -LiteralPath $CacheRoot).Path)
if ($Publish) { $items += '--publish' }
$quoted = foreach ($item in $items) {
    if ($item.Contains('"')) { throw 'Unexpected quote in argument' }
    '"' + $item.TrimEnd('\') + '"'
}
$action = New-ScheduledTaskAction -Execute $python -Argument ($quoted -join ' ')
$trigger = New-ScheduledTaskTrigger -Daily -At '19:00'
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -Force -TaskName 'QingfengMusic-DailyRelease' -Action $action -Trigger $trigger -Settings $settings -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
