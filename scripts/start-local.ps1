[CmdletBinding()]
param([switch]$CheckOnly,[switch]$AllowProductionConnection,[switch]$Dev,[switch]$Lan,[switch]$PaymentDemo,[string]$DemoConfig,[switch]$PaypalLive,[string]$PaypalConfig,[int]$Port=8080,[int]$WebPort=5173)
$ErrorActionPreference='Stop'
if($PaymentDemo -or $DemoConfig){throw '已按用户要求停用独立演示库；日常使用线上主库，不将模拟付款写入主库。'}
if($PaypalLive -or $PaypalConfig){throw '日常与真实支付统一使用已上线主库版本；不再启动本地支付专库，请直接运行 scripts/start-main.ps1。'}
if($Lan -or $Port -ne 8080 -or $WebPort -ne 5173){throw '主库入口固定本机 8080/5173，以保持登录回调与访问范围一致。'}
& (Join-Path $PSScriptRoot 'start-main.ps1') -CheckOnly:$CheckOnly -AllowProductionConnection:$AllowProductionConnection
