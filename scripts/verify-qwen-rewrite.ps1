# Real-call acceptance test for the AI rewrite provider (qwen3.8-flash).
# Usage (PowerShell 5.1 blocks .ps1 by default):
#   powershell -ExecutionPolicy Bypass -File scripts/verify-qwen-rewrite.ps1
# The API key is read ONLY from the environment variable aliyunqingfengKEY.
# It is never printed, logged, or written into the repository.
# Override with QWEN_MODEL / QWEN_BASE_URL to match backend config.
# NOTE: keep this file pure ASCII; Windows PowerShell 5.1 reads .ps1 as ANSI.

$ErrorActionPreference = 'Stop'

$key = [Environment]::GetEnvironmentVariable('aliyunqingfengKEY', 'User')
if ([string]::IsNullOrWhiteSpace($key)) { $key = $env:aliyunqingfengKEY }
if ([string]::IsNullOrWhiteSpace($key)) {
    Write-Output 'FAIL: environment variable aliyunqingfengKEY is not set (User scope or current process)'
    exit 1
}
$key = $key.Trim()

$model = 'qwen3.8-flash'
if ($env:QWEN_MODEL) { $model = $env:QWEN_MODEL }
$base = 'https://maas.qianwenaiapi.com/compatible-mode/v1'
if ($env:QWEN_BASE_URL) { $base = $env:QWEN_BASE_URL }
$uri = $base.TrimEnd('/') + '/chat/completions'

# Matches DeepSeekInspirationRewriter request body: NO DeepSeek-only "thinking" field.
$payload = @{
    model           = $model
    messages        = @(
        @{ role = 'system'; content = 'Return ONLY a JSON object like {"text":"rewritten text"}. No explanation, no Markdown.' },
        @{ role = 'user'; content = '{"text":"a song about summer","lyricLanguage":"zh-CN"}' }
    )
    response_format = @{ type = 'json_object' }
    max_tokens      = 200
    stream          = $false
} | ConvertTo-Json -Depth 10

$tmp = Join-Path $env:TEMP 'qf_qwen_rewrite_request.json'
$out = Join-Path $env:TEMP 'qf_qwen_rewrite_response.json'
[System.IO.File]::WriteAllText($tmp, $payload, (New-Object System.Text.UTF8Encoding($false)))

$code = & curl.exe -s --max-time 45 -o $out -w '%{http_code}' -X POST $uri `
    -H "Authorization: Bearer $key" -H 'Content-Type: application/json; charset=utf-8' `
    --data-binary "@$tmp" 2>$null

Write-Output "MODEL=$model"
Write-Output "ENDPOINT=$uri"
Write-Output "HTTP=$code"

$raw = [System.IO.File]::ReadAllText($out)
if ($code -ne '200') {
    if ($raw.Length -gt 500) { $raw = $raw.Substring(0, 500) }
    Write-Output "FAIL: real call did not succeed, response=$raw"
    exit 1
}

$resp = $raw | ConvertFrom-Json
$choice = $resp.choices[0]
Write-Output "FINISH_REASON=$($choice.finish_reason)"
$content = $choice.message.content | ConvertFrom-Json
Write-Output "REWRITE_TEXT=$($content.text)"
Write-Output "USAGE_PROMPT=$($resp.usage.prompt_tokens) USAGE_COMPLETION=$($resp.usage.completion_tokens)"
Write-Output 'VERIFY_OK: auth, request protocol and JSON response verified by a real call'
