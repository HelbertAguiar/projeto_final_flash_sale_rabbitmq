param(
    [ValidateSet('real')][string]$Mode = 'real'
)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
$oldDemo = $env:FLASHSALE_DEMO_ENABLED
$oldFailure = $env:PROCESSOR_FAILURE_MODE
try {
    $env:FLASHSALE_DEMO_ENABLED = 'true'
    $env:PROCESSOR_FAILURE_MODE = 'never'
    & docker compose up -d --build --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw 'Falha na inicializacao REAL; consulte docker compose logs.' }
    & docker compose ps
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao consultar containers.' }
} finally {
    $env:FLASHSALE_DEMO_ENABLED = $oldDemo
    $env:PROCESSOR_FAILURE_MODE = $oldFailure
    Pop-Location
}
