$ErrorActionPreference = 'Stop'
$backendDir = $PSScriptRoot
$frontendDir = 'C:\Users\economy\Desktop\dev\financial-helper-frontend'
$runId = 'r' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$containerName = 'fh-o03b-e2e-' + $runId
$databaseName = 'financial_helper_e2e_' + $runId
$databaseUser = 'e2e_' + $runId
$containerId = $null
$frontend = $null
$tempOut = [System.IO.Path]::GetTempFileName()
$tempErr = [System.IO.Path]::GetTempFileName()
try {
    if (-not (Test-Path -LiteralPath (Join-Path $frontendDir 'node_modules\next\dist\bin\next'))) { throw 'Frontend Next.js dependencies unavailable' }
    foreach ($port in @(3000, 8080)) {
        $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
        try { $probe.Start() } finally { $probe.Stop() }
    }
    & docker image inspect postgres:16-alpine --format '{{.Id}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Existing postgres:16-alpine image required; no download attempted' }
    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try { $probe.Start(); $port = ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port } finally { $probe.Stop() }
    $env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $containerId = (& docker run -d --pull=never --name $containerName --label "financial-helper.e2e.run=$runId" --tmpfs '/var/lib/postgresql/data:rw,nosuid,size=256m' -p "127.0.0.1:${port}:5432" -e "POSTGRES_DB=$databaseName" -e "POSTGRES_USER=$databaseUser" -e POSTGRES_PASSWORD postgres:16-alpine).Trim()
    if ($LASTEXITCODE -ne 0 -or $containerId -notmatch '^[a-f0-9]{64}$') { throw 'Container creation failed' }
    $name = (& docker inspect --format '{{.Name}}' $containerId).Trim()
    $label = (& docker inspect --format '{{index .Config.Labels "financial-helper.e2e.run"}}' $containerId).Trim()
    $mapped = (& docker port $containerId 5432/tcp).Trim()
    $mount = & docker exec $containerId cat /proc/mounts
    if ($name -ne "/$containerName" -or $label -ne $runId -or $mapped -ne "127.0.0.1:$port" -or -not ($mount -match '^\S+ /var/lib/postgresql/data tmpfs ')) { throw 'Container isolation verification failed' }
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        & docker exec $containerId pg_isready -U $databaseUser -d $databaseName *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready' }
    $env:E2E_RUN_ID = $runId
    $env:E2E_CONTAINER_ID = $containerId
    $env:E2E_DB_URL = "jdbc:postgresql://127.0.0.1:$port/$databaseName"
    $env:E2E_DB_USERNAME = $databaseUser
    $env:E2E_DB_PASSWORD = $env:POSTGRES_PASSWORD
    $env:SPRING_PROFILES_ACTIVE = 'browser-e2e'
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
    $env:NEXT_PUBLIC_API_BASE_URL = 'http://localhost:8080/api/v1'
    if (Test-Path Env:SPRING_DATASOURCE_URL) { throw 'SPRING_DATASOURCE_URL override is forbidden' }
    Push-Location $frontendDir
    try {
        & npm run build *> $null
        if ($LASTEXITCODE -ne 0) { throw "Frontend build failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    $frontend = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @('node_modules/next/dist/bin/next','start','--hostname','localhost','--port','3000') -WorkingDirectory $frontendDir -WindowStyle Hidden -RedirectStandardOutput $tempOut -RedirectStandardError $tempErr -PassThru
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        try { $response = Invoke-WebRequest -Uri 'http://localhost:3000/' -TimeoutSec 2; if ($response.StatusCode -eq 200) { $ready = $true; break } } catch {}
        if ($frontend.HasExited) { throw 'Frontend process exited' }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Frontend did not become ready' }
    Write-Output "E2E container=$containerId name=$containerName label=$runId port=$port db=$databaseName user=$databaseUser tmpfs=verified"
    Push-Location $backendDir
    try {
        $output = @('auth-browser', 'seed-card', 'card-browser', 'exit') | & .\gradlew.bat browserE2eServer --offline --no-daemon 2>&1
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    $output | Where-Object { $_ -match 'E2E_STAGE_|AUTH_BROWSER_VERIFIED|BROWSER_E2E_READY|SYNTHETIC_CARD_FIXTURE_READY|CARD_BROWSER_HTTP_VERIFIED|FIXTURE_COMMAND_REJECTED|BUILD SUCCESSFUL|BUILD FAILED' } | ForEach-Object { Write-Output $_ }
    if ($code -ne 0 -or -not ($output -match 'AUTH_BROWSER_VERIFIED') -or -not ($output -match 'SYNTHETIC_CARD_FIXTURE_READY') -or -not ($output -match '^CARD_BROWSER_HTTP_VERIFIED$') -or ($output -match 'FIXTURE_COMMAND_REJECTED')) { throw "Browser E2E failed or incomplete (Gradle exit $code)" }
} finally {
    if ($frontend -and -not $frontend.HasExited) { Stop-Process -Id $frontend.Id -ErrorAction SilentlyContinue }
    if ($containerId -and $containerId -match '^[a-f0-9]{64}$') {
        $name = (& docker inspect --format '{{.Name}}' $containerId 2>$null).Trim()
        $label = (& docker inspect --format '{{index .Config.Labels "financial-helper.e2e.run"}}' $containerId 2>$null).Trim()
        if ($name -eq "/$containerName" -and $label -eq $runId) { & docker rm -f $containerId | Out-Null; Write-Output "E2E_CONTAINER_REMOVED $containerId" }
        else { Write-Warning "Container identity mismatch; not removed: $containerId" }
    }
    Remove-Item -LiteralPath $tempOut, $tempErr -ErrorAction SilentlyContinue
    Remove-Item Env:POSTGRES_PASSWORD, Env:E2E_DB_PASSWORD -ErrorAction SilentlyContinue
}