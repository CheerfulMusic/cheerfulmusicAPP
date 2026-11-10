[CmdletBinding()]
param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
# All supported build entry points converge; do not bypass frontend verification.
& (Join-Path $workspaceRoot 'scripts/build-local.ps1') -JavaHome $JavaHome
