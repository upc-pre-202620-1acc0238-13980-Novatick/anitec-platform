#requires -Version 7.0
<#
Windows-only disposable startup laboratory. Run in a separate pwsh process, not dot-sourced.
Never uses anitec-pg or a persistent database. No provider credentials are needed or printed.
#>
[CmdletBinding()]
param(
    [ValidateRange(1024,65535)][int]$DatabasePort=15432,
    [ValidateRange(1024,65535)][int]$ApplicationPort=18080,
    [switch]$EnablePaymentSimulation,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'This helper requires Windows and PowerShell 7; use Maven Wrapper directly on other systems.' }
if ($DatabasePort -eq $ApplicationPort) { throw 'The two laboratory ports must differ.' }
function New-LabKey { $bytes=[byte[]]::new(32);$rng=[System.Security.Cryptography.RandomNumberGenerator]::Create();try {$rng.GetBytes($bytes);[Convert]::ToBase64String($bytes)}finally{$rng.Dispose()} }
$repoRoot=Split-Path -Parent $PSScriptRoot
$labName='anitec-docs-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$created=$false
$backend=$null
$outputTask=$null
$errorTask=$null
try {
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine must be available.' }
    $env:POSTGRES_PASSWORD=New-LabKey
    & docker run --detach --rm --name $labName --publish "127.0.0.1:${DatabasePort}:5432" --tmpfs '/var/lib/postgresql/data:rw' --env POSTGRES_PASSWORD --env POSTGRES_USER=anitec_lab --env POSTGRES_DB=anitec_docs postgres:16-alpine *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated database. Check the selected port and Docker image.' }
    $created=$true
    $ready=$false
    for ($i=0;$i -lt 40;$i++) {
        & docker exec $labName pg_isready -U anitec_lab -d anitec_docs *> $null
        if ($LASTEXITCODE -eq 0) {$ready=$true;break}
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'The temporary PostgreSQL instance did not become ready.' }
    # Values exist only in this child process and the child backend; nothing is written to Git or disk.
    foreach ($name in @('DATABASE_URL','EMAIL_OUTBOX_ENCRYPTION_KEY','PASSWORD_RECOVERY_HMAC_KEY','STAGING_ACCESS_KEY','RESEND_FROM')) {
        [Environment]::SetEnvironmentVariable($name,'','Process')
    }
    $env:SPRING_PROFILES_ACTIVE='dev'
    $env:SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${DatabasePort}/anitec_docs"
    $env:SPRING_DATASOURCE_USERNAME='anitec_lab'
    $env:SPRING_DATASOURCE_PASSWORD=$env:POSTGRES_PASSWORD
    $env:EMAIL_VERIFICATION_HMAC_KEY=New-LabKey
    $env:SESSION_RECOVERY_KEY=New-LabKey
    $env:JWT_SECRET=New-LabKey
    $env:RESEND_API_KEY=''
    $env:EMAIL_DELIVERY_MODE='disabled'
    $env:DEMO_SEED='false'
    $env:PORT=[string]$ApplicationPort
    $env:SPRING_APPLICATION_JSON=@{
        'server.port'=$ApplicationPort;'server.address'='127.0.0.1'
        'springdoc.api-docs.enabled'=$true;'springdoc.swagger-ui.enabled'=$true
        'email.delivery.mode'='disabled';'email.delivery.worker-enabled'=$false
        'identity.password-recovery.enabled'=$false
        'payments.simulation-enabled'=[bool]$EnablePaymentSimulation
        'spring.flyway.placeholders.demo_seed'=$false
        'jwt.secret'=$env:JWT_SECRET
        'identity.sessions.recovery-key'=$env:SESSION_RECOVERY_KEY
        'identity.email-verification.hmac-key'=$env:EMAIL_VERIFICATION_HMAC_KEY
        'logging.level.root'='WARN'
    } | ConvertTo-Json -Compress
    $start=[System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$env:ComSpec
    $start.ArgumentList.Add('/d');$start.ArgumentList.Add('/c');$start.ArgumentList.Add('.\mvnw.cmd -B spring-boot:run')
    $start.WorkingDirectory=$repoRoot
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WindowStyle='Hidden'
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $backend=[System.Diagnostics.Process]::Start($start)
    $outputTask=$backend.StandardOutput.ReadToEndAsync();$errorTask=$backend.StandardError.ReadToEndAsync()
    $base="http://127.0.0.1:$ApplicationPort"
    $healthy=$false
    for ($i=0;$i -lt 240;$i++) {
        if ($backend.HasExited) { throw 'The laboratory backend exited before health became UP. No raw logs or environment values are printed.' }
        try { $health=Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2;if ($health.status -eq 'UP') {$healthy=$true;break} } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) { throw 'The laboratory backend did not become healthy within the allowed time.' }
    $spec=Invoke-RestMethod "$base/v3/api-docs" -TimeoutSec 30
    if (-not $spec.openapi) { throw 'The local OpenAPI endpoint returned no specification.' }
    $migration=& docker exec $labName psql -U anitec_lab -d anitec_docs -Atc 'SELECT count(*),max(version::integer) FROM flyway_schema_history WHERE success;'
    if ($LASTEXITCODE -ne 0 -or $migration.Trim() -ne '18|18') { throw 'The temporary schema did not reach exactly V1-V18.' }
    $private=Invoke-WebRequest "$base/api/v1/account/me" -SkipHttpErrorCheck
    $recovery=Invoke-WebRequest "$base/api/v1/auth/password-recovery/request" -Method Post -ContentType 'application/json' -Body '{"email":"probe@example.invalid"}' -SkipHttpErrorCheck
    if ($private.StatusCode -ne 401 -or $recovery.StatusCode -ne 503 -or ($recovery.Content|ConvertFrom-Json).errorCode -ne 'RECOVERY_UNAVAILABLE') { throw 'The local security checks did not match the documented behavior.' }
    Write-Output "Local laboratory ready: health UP; OpenAPI $($spec.openapi); Flyway V1-V18; private route 401; recovery disabled 503."
    Write-Output "Backend: $base — temporary PostgreSQL on localhost:$DatabasePort; no email providers enabled."
    if (-not $ValidateOnly) {
        Write-Output 'Press Ctrl+C to stop the backend and remove only this disposable database. All laboratory data will be lost.'
        while (-not $backend.HasExited) { Start-Sleep -Seconds 1 }
    }
} finally {
    if ($null -ne $backend) {
        if (-not $backend.HasExited) {$backend.Kill($true)}
        $backend.WaitForExit();$backend.Dispose()
    }
    if ($created -and $labName -match '^anitec-docs-[a-f0-9]{12}$') {
        & docker rm --force $labName *> $null
    }
    Write-Output 'Owned temporary laboratory resources cleaned up; persistent containers were not selected.'
}
