param(
    [Parameter(Mandatory=$true)][ValidateSet('mock','real')][string]$Mode,
    [ValidateSet('checkout-service','order-processor-service')][string]$Service
)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    if ($Mode -eq 'real') {
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
        }
    } else {
        if (-not $Service) { throw 'Informe -Service checkout-service ou order-processor-service.' }
        $oldPath = $env:PATH
        try {
            if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH }
            # java -version escreve em stderr; usar cmd evita NativeCommandError no PowerShell 5.1.
            $javaVersion = & cmd /c 'java -version 2>&1'
            if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch 'version "21\.') { throw 'Configure JAVA_HOME com JDK 21.' }
            $mavenVersion = & mvn -version
            if ($LASTEXITCODE -ne 0 -or ($mavenVersion -join ' ') -notmatch 'Java version: 21\.') { throw 'Maven deve usar JDK 21.' }
            & mvn -B -ntp -f "$Service/pom.xml" package '-DskipTests' '-Dquarkus.profile=mock'
            if ($LASTEXITCODE -ne 0) { throw 'Falha na compilacao.' }
            & java '-Dquarkus.profile=mock' '-Dflashsale.real=false' '-Dflashsale.demo.enabled=true' '-Dprocessor.mock.failure-mode=never' '-Dcheckout.events.transport=http' -jar "$Service/target/quarkus-app/quarkus-run.jar"
            if ($LASTEXITCODE -ne 0) { throw 'A JVM encerrou com erro.' }
        } finally { $env:PATH = $oldPath }
    }
} finally { Pop-Location }
