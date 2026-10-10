# Download OmniParser v2 weights (~1.3 GB) into omniparser-service\weights\.
#
#   powershell -ExecutionPolicy Bypass -File .\download_weights.ps1
#   powershell -ExecutionPolicy Bypass -File .\download_weights.ps1 -Endpoint https://huggingface.co
#
# Produces:
#   weights\icon_detect_v3\model.pt              (YOLOv9 icon detector, revision refs/pr/37)
#   weights\icon_caption_florence\{config.json,generation_config.json,model.safetensors}
#   weights\florence2_base_processor\...         (microsoft/Florence-2-base processor files)
#
# Endpoint handling, verified 2026-10-10:
#   hf-mirror.com answers HTTP 308 (redirect to huggingface.co) on /resolve/ URLs;
#   huggingface_hub probes metadata with allow_redirects=False and therefore fails
#   with "Distant resource does not seem to be on huggingface.co".  The mirror is
#   still tried first (default), and the official endpoint is used as a fallback.

[CmdletBinding()]
param(
    [string]$Python = "D:\Python312\python.exe",
    [string]$WeightsDir = (Join-Path $PSScriptRoot "weights"),
    [string]$Endpoint = "https://hf-mirror.com",
    [string]$FallbackEndpoint = "https://huggingface.co"
)

$ErrorActionPreference = "Stop"
Write-Host "[download_weights] python   = $Python"
Write-Host "[download_weights] weights  = $WeightsDir"
Write-Host "[download_weights] endpoint = $Endpoint"
if ($FallbackEndpoint) { Write-Host "[download_weights] fallback = $FallbackEndpoint" }

if (-not (Test-Path $Python)) {
    Write-Error "python not found at $Python (pass -Python <path>)"
    exit 1
}

$script = Join-Path $PSScriptRoot "download_weights.py"

& $Python $script --weights-dir $WeightsDir --endpoint $Endpoint
$code = $LASTEXITCODE

if ($code -ne 0 -and $FallbackEndpoint -and $FallbackEndpoint -ne $Endpoint) {
    Write-Host "[download_weights] endpoint $Endpoint failed (exit=$code), retrying with $FallbackEndpoint"
    & $Python $script --weights-dir $WeightsDir --endpoint $FallbackEndpoint
    $code = $LASTEXITCODE
}

Write-Host "[download_weights] exit=$code"
exit $code
