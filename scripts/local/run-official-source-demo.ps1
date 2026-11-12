param([string]$FrontendDirectory = 'C:\Users\economy\Desktop\dev\financial-helper-frontend',
      [switch]$WithFrontend, [switch]$WithKure)
$ErrorActionPreference = 'Stop'
$backendDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$run = [Guid]::NewGuid().ToString('N')
$containerName = 'fh-official-demo-' + $run
$databaseName = 'fh_official_demo_' + $run
$databaseUser = 'fh_demo_' + $run.Substring(0, 12)
$containerId = $null
$backendProcess = $null
$frontendProcess = $null
$kureProcess = $null
$kureIndex = $null
$ownedFiles = @()
$demoReady = $false

function Assert-AvailablePort([int]$value) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $value)
    try { $listener.Start() } finally { $listener.Stop() }
}
function Get-ContainerIdentity([string]$id) {
    $inspection = (& docker inspect $id 2>$null | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0 -or -not $inspection) { throw 'Demo container inspection failed' }
    return $inspection
}
try {
    foreach ($variable in @('DB_URL', 'TEST_DB_URL', 'E2E_DB_URL', 'SPRING_DATASOURCE_URL',
            'DB_USERNAME', 'DB_PASSWORD', 'TEST_DB_PASSWORD', 'E2E_DB_PASSWORD')) {
        if ([Environment]::GetEnvironmentVariable($variable)) { throw "Existing database variable $variable forbids demo startup" }
    }
    Assert-AvailablePort 8080
    if ($WithFrontend) { Assert-AvailablePort 3000 }
    & docker image inspect postgres:16-alpine --format '{{.Id}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Existing postgres:16-alpine image is required' }
    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try { $probe.Start(); $dbPort = ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port } finally { $probe.Stop() }
    $env:POSTGRES_DB = $databaseName
    $env:POSTGRES_USER = $databaseUser
    $env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $containerId = (& docker run -d --rm --pull=never --name $containerName `
            --label "financial-helper.official-demo.run=$run" `
            --tmpfs '/var/lib/postgresql/data:rw,nosuid,size=256m' `
            -p "127.0.0.1:${dbPort}:5432" -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD postgres:16-alpine).Trim()
    if ($LASTEXITCODE -ne 0 -or $containerId -notmatch '^[a-f0-9]{64}$') { throw 'Demo container creation failed' }
    $identity = Get-ContainerIdentity $containerId
    $binding = $identity.NetworkSettings.Ports.'5432/tcp'
    if ($identity.Id -ne $containerId -or $identity.Name -ne "/$containerName" -or
            $identity.Config.Labels.'financial-helper.official-demo.run' -ne $run -or
            $binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or
            $binding[0].HostPort -ne "$dbPort" -or
            -not $identity.HostConfig.Tmpfs.'/var/lib/postgresql/data' -or $identity.Mounts.Count -ne 0) {
        throw 'Demo container isolation verification failed'
    }
    $mounts = & docker exec $containerId cat /proc/mounts
    if ($LASTEXITCODE -ne 0 -or -not ($mounts -match '^\S+ /var/lib/postgresql/data tmpfs ')) {
        throw 'Demo container tmpfs verification failed'
    }
    $env:FH_DEMO_RUN_ID = $run
    $env:FH_DEMO_CONTAINER_ID = $containerId
    $env:FH_DEMO_DB_URL = "jdbc:postgresql://127.0.0.1:$dbPort/$databaseName"
    $env:FH_DEMO_DB_USERNAME = $databaseUser
    $env:FH_DEMO_DB_PASSWORD = $env:POSTGRES_PASSWORD
    $env:SPRING_PROFILES_ACTIVE = 'official-demo'
    $env:AI_ENABLED = 'false'
    $env:LAW_OPEN_API_ENABLED = 'false'
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        & docker exec $containerId pg_isready -U $databaseUser -d $databaseName *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($LASTEXITCODE -ne 0) { throw 'Demo PostgreSQL did not become ready' }
    if ($WithKure) {
        $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        try { $probe.Start(); $kurePort = ([System.Net.IPEndPoint]$probe.LocalEndpoint).Port } finally { $probe.Stop() }
        $kureIndex = Join-Path ([System.IO.Path]::GetTempPath()) ('fh-official-demo-index-' + $run)
        New-Item -ItemType Directory -Path $kureIndex | Out-Null
        $kureOut = [System.IO.Path]::GetTempFileName(); $kureErr = [System.IO.Path]::GetTempFileName()
        $ownedFiles += $kureOut, $kureErr
        $kureProcess = Start-Process -FilePath (Get-Command python.exe).Source `
                -ArgumentList @('retrieval_runtime/runtime.py','--host','127.0.0.1','--port',"$kurePort",'--index-root',$kureIndex) `
                -WorkingDirectory $backendDirectory -WindowStyle Hidden -RedirectStandardOutput $kureOut -RedirectStandardError $kureErr -PassThru
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ($kureProcess.HasExited) { throw 'Local KURE runtime exited' }
            try { if ((Invoke-WebRequest -Uri "http://127.0.0.1:$kurePort/v1/runtime" -TimeoutSec 2).StatusCode -eq 200) { break } } catch {}
            Start-Sleep -Seconds 1
        }
        if ($attempt -ge 60) { throw 'Local KURE runtime not ready' }
        $env:KURE_RUNTIME_ENABLED = 'true'
        $env:KURE_RUNTIME_ENDPOINT = "http://127.0.0.1:$kurePort"
    } else { $env:KURE_RUNTIME_ENABLED = 'false' }
    Push-Location $backendDirectory
    try { & .\gradlew.bat bootJar --offline --no-daemon *> $null; if ($LASTEXITCODE -ne 0) { throw 'Backend bootJar failed' } }
    finally { Pop-Location }
    $backendOut = [System.IO.Path]::GetTempFileName(); $backendErr = [System.IO.Path]::GetTempFileName()
    $ownedFiles += $backendOut, $backendErr
    $jar = Join-Path $backendDirectory 'build/libs/financial-helper-backend-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Expected backend jar is missing' }
    $backendProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar',$jar) `
            -WorkingDirectory $backendDirectory -WindowStyle Hidden -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr -PassThru
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($backendProcess.HasExited) { throw 'Backend exited before readiness' }
        try { if ((Invoke-WebRequest -Uri 'http://127.0.0.1:8080/health' -TimeoutSec 2).StatusCode -eq 200) { break } } catch {}
        Start-Sleep -Seconds 1
    }
    if ($attempt -ge 90) { throw 'Backend not ready' }
    $registrySql = Join-Path $PSScriptRoot 'official-demo-source-registry.sql'
    if (-not (Test-Path -LiteralPath $registrySql -PathType Leaf)) { throw 'Demo registry SQL file is missing' }
    $containerSql = '/tmp/official-demo-source-registry.sql'
    & docker cp $registrySql "${containerId}:$containerSql" *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Demo registry SQL copy failed' }
    & docker exec $containerId psql -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -f $containerSql *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Demo source registry registration failed' }
    $registryCount = (& docker exec $containerId psql -U $databaseUser -d $databaseName -tA `
            -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM source_registry WHERE source_key='fsc-deposit-protection-qa-pdf'").Trim()
    if ($LASTEXITCODE -ne 0 -or $registryCount -ne '1') { throw 'Demo source registry verification failed' }
    if ($WithFrontend) {
        $FrontendDirectory = (Resolve-Path -LiteralPath $FrontendDirectory).Path
        $env:NEXT_PUBLIC_API_BASE_URL = 'http://localhost:8080/api/v1'
        $env:NEXT_PUBLIC_OFFICIAL_REVIEW_ENABLED = 'true'
        Push-Location $FrontendDirectory
        try { & npm run build *> $null; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' } }
        finally { Pop-Location }
        $frontendOut = [System.IO.Path]::GetTempFileName(); $frontendErr = [System.IO.Path]::GetTempFileName()
        $ownedFiles += $frontendOut, $frontendErr
        $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source `
                -ArgumentList @('node_modules/next/dist/bin/next','start','--hostname','127.0.0.1','--port','3000') `
                -WorkingDirectory $FrontendDirectory -WindowStyle Hidden `
                -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr -PassThru
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            if ($frontendProcess.HasExited) { throw 'Frontend exited before readiness' }
            try { if ((Invoke-WebRequest -Uri 'http://127.0.0.1:3000/' -TimeoutSec 2).StatusCode -eq 200) { break } } catch {}
            Start-Sleep -Seconds 1
        }
        if ($attempt -ge 30) { throw 'Frontend not ready' }
        if ((Invoke-WebRequest -Uri 'http://localhost:3000/' -TimeoutSec 3).StatusCode -ne 200) {
            throw 'Frontend localhost origin is not reachable'
        }
        if ((Invoke-WebRequest -Uri 'http://localhost:3000/official-source-review/deposit' -TimeoutSec 3).StatusCode -ne 200) {
            throw 'Reviewer page is not reachable in the dedicated demo build'
        }
    }
    Write-Output "OFFICIAL_DEMO_READY run=$run container=$containerId db=$databaseName port=$dbPort tmpfs=verified"
    $demoReady = $true
    Write-Output 'The official PDF is registered as a candidate only. No reviewer grant, APPROVED review, or READY generation was created.'
    Read-Host 'Press Enter to stop only this demo run'
} finally {
    foreach ($owned in @($frontendProcess, $backendProcess, $kureProcess)) {
        if ($owned -and -not $owned.HasExited) { Stop-Process -Id $owned.Id -ErrorAction SilentlyContinue }
    }
    if ($containerId -match '^[a-f0-9]{64}$') {
        $current = Get-ContainerIdentity $containerId
        if ($current.Id -eq $containerId -and $current.Name -eq "/$containerName" -and
                $current.Config.Labels.'financial-helper.official-demo.run' -eq $run) {
            & docker rm -f $containerId *> $null
            if ($LASTEXITCODE -ne 0) { throw 'Owned demo container cleanup failed' }
        } else { throw 'Demo container identity changed; no container was removed' }
    }
    foreach ($file in $ownedFiles) {
        if (Test-Path -LiteralPath $file) {
            if ($demoReady) { Remove-Item -LiteralPath $file -Force }
            else { Write-Output "DEMO_DIAGNOSTIC_LOG=$file" }
        }
    }
    if ($kureIndex -and (Test-Path -LiteralPath $kureIndex)) {
        $expected = Join-Path ([System.IO.Path]::GetTempPath()) ('fh-official-demo-index-' + $run)
        if ((Resolve-Path -LiteralPath $kureIndex).Path -eq $expected) {
            Remove-Item -LiteralPath $kureIndex -Recurse -Force
        }
    }
    foreach ($variable in @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','FH_DEMO_RUN_ID',
            'FH_DEMO_CONTAINER_ID','FH_DEMO_DB_URL','FH_DEMO_DB_USERNAME','FH_DEMO_DB_PASSWORD',
            'SPRING_PROFILES_ACTIVE','AI_ENABLED','LAW_OPEN_API_ENABLED','KURE_RUNTIME_ENABLED',
            'KURE_RUNTIME_ENDPOINT','NEXT_PUBLIC_API_BASE_URL','NEXT_PUBLIC_OFFICIAL_REVIEW_ENABLED')) {
        Remove-Item -Path "Env:$variable" -ErrorAction SilentlyContinue
    }
}
